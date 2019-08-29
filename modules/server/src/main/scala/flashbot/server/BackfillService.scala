package flashbot.server

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

import akka.actor.{Actor, ActorLogging}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.slick.javadsl.SlickSession
import flashbot.core.DataSource
import flashbot.util.stream._
import flashbot.db._
import flashbot.core.DeltaFmtJson
import flashbot.models.DataPath
import io.circe.Printer
import io.circe.syntax._
import slick.jdbc.TransactionIsolation

import scala.collection.immutable
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.blocking
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

/**
  * Any number of BackfillServices can be run in the cluster concurrently. Due to the
  * locking mechanism, they do not conflict with one another, even if they are assigned
  * to the same paths.
  *
  * On initialization:
  *   1. Generate a random id for this actor instance.
  *   2. Create new "unclaimed" and "incomplete" backfill record with a null cursor and
  *      set `nextPageAt` to the epoch.
  *
  * On every tick:
  *   1. Claim the most recent available backfill. Available means: unclaimed and a
  *      `nextPageAt` value in the past.
  *   2. Fetch the data at the current cursor.
  *   3. Persist the data with the given bundle id, backfill id, and correct `seqid`s.
  *   4. Release the lock by unclaiming the record, and setting the `cursor` and `nextPageAt`
  *      columns according to whether the persistence function said this backfill is complete.
  *   5. Schedule the next tick.
  */
class BackfillService[T](bundleId: Long, path: DataPath[T], dataSource: DataSource)
                        (implicit session: SlickSession) extends Actor with ActorLogging {
  import session.profile.api._

  case object BackfillTick

  implicit val system = context.system
  implicit val mat = buildMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher
  val random = new Random()

  implicit val fmt: DeltaFmtJson[T] = path.fmt[T]

  val instanceId = UUID.randomUUID().toString
  val tickRate = dataSource.backfillTickRate

  // If a claim is held for more than 10 seconds, something is probably wrong. Expire it.
  val ClaimTTL = (10 seconds) / tickRate

  // Create a new row
  val insertedBackfills = Await.result(session.db.run(
    Backfills +=
      BackfillRow(bundleId, path.source, path.topic, path.datatype, None,
        Some(Timestamp.from(Instant.EPOCH)), None, None)), 5 seconds)
  if (insertedBackfills == 0) {
    throw new RuntimeException(s"Unable to create backfill row $bundleId")
  }

  def scheduleNextTick(): Unit = {
    system.scheduler.scheduleOnce(((2 seconds) +
      (random.nextInt(5000) millis)) / tickRate)(self ! BackfillTick)
  }
  self ! BackfillTick

  def claimedBackfill = Backfills.filter(_.claimedBy === instanceId)

  def availableBackfills(now: Timestamp) = Backfills
    .forPath(path)
    // Not claimed by anyone else
    .filter(_.claimedBy.isEmpty)
    .filter(_.claimedAt.isEmpty)
    // It is not complete and is not currently throttled.
    .filter(_.nextPageAt < now)
    .sortBy(_.bundle.desc)

  override def receive = {
    case BackfillTick =>
      val now = Instant.now()
      val nowts = Timestamp.from(now)

      val hasClaimedOpt = session.db.run((for {
        // First, perform some global clean-up. Ensure that all stale locks are released.
        numExpired <- Backfills
          .filter(_.claimedAt < Timestamp.from(now.minusMillis(ClaimTTL.toMillis)))
          .map(bf => (bf.claimedBy, bf.claimedAt))
          .update(None, None)
        _ = { if (numExpired > 0) log.warning("Expired {} backfill claims", numExpired) }

        // Next, try to claim the next available backfill.
        // Gotta do this in two steps due to a bug in Slick:
        // https://github.com/slick/slick/issues/1672
        idToClaim <- availableBackfills(nowts).map(_.bundle).forUpdate.result.headOption
        claimed <- Backfills
          .filter(_.bundle === idToClaim)
          .map(bf => (bf.claimedBy, bf.claimedAt))
          .update(Some(instanceId), Some(nowts))
          .map {
            // Successful claim.
            case 1 => true
            // Unable to claim for whatever reason. Ignore.
            case 0 => false
          }
      } yield claimed).transactionally)

      hasClaimedOpt.onComplete {
        case Success(true) =>
          // We just claimed a path. Let's get to work!
          runPage(now) andThen {
            case Success(data) =>
              log.debug("Backfilled data for {}", path)
              scheduleNextTick()
            case Failure(err) =>
              log.error(err, "Backfill error. Stopping backfill instance {} / {}", path, instanceId)
              context.stop(self)
          }
        case Success(false) =>
          scheduleNextTick()
        case Failure(err) =>
          log.error(err, "An error occurred during backfill scheduling for {}", path)
          scheduleNextTick()
      }
  }

  /**
    * This is where we do the work of requesting data from the data source, persisting the
    * historical market data, and updating the backfill record accordingly. We are guaranteed
    * that we have the claim at this point.
    */
  def runPage(now: Instant) = {
    implicit val itemEn = fmt.modelEn
    implicit val deltaEn = fmt.deltaEn

    log.debug("Running backfill page for {}", path)

    def proceed(claim: BackfillRow, res: (Vector[(Long, T)], Option[(String, FiniteDuration)])) =
      res match {
        case (rspData, nextCursorOpt) =>
          for {
            // Ensure the data isn't backwards. It can be easy to mess this up.
            // The first element should be the most recent!
            data <-
              if (rspData.size >= 2 && path.datatype.ordering.compare(rspData.head._2, rspData.last._2) < 0)
                DBIO.failed(new IllegalStateException(
                  s"Backfill data out of order. It must be in reverse chronological order."))
              else DBIO.successful(rspData.reverse)

            // Find if there is any overlap with this page and existing data. If there is,
            // still insert the data, but don't continue backfilling. Use a 1 second grace period
            // because exchanges don't have perfect time.
            dataStartMicros = data.headOption.map(_._1 + 1000 * 1000)
            overlapSnaps <- Snapshots.forPath(path)
              .filter(x => x.micros > dataStartMicros && x.bundle < claim.bundle)
              .size.result
            overlapDeltas <- Deltas.forPath(path)
              .filter(x => x.micros > dataStartMicros && x.bundle < claim.bundle)
              .size.result
            overlapItems = overlapSnaps + overlapDeltas

            _ = log.debug(s"Fetched backfill page ({} items) for path {}", data.size, path)

            // We got some data from the backfill. Let's insert it and schedule the next page.
            // Find the earliest seqid for this bundle. Only look at snapshots. There should
            // never be backfill deltas that predate the earliest snapshot.
            earliestSeqIdOpt <- Snapshots
                .filter(_.bundle === claim.bundle)
                .map(_.seqid).min.result

            seqIdBound = earliestSeqIdOpt.getOrElse(0L)
            seqIdStart: Long = seqIdBound - data.size

            _ = log.debug(s"Backfill info: path={}, seq bound={}, bundle={}", path, seqIdBound, claim.bundle)

            // Insert the snapshot
            _ <- data.headOption.map {
              case (micros, item) =>
                val snapRow = SnapshotRow(0, claim.bundle, seqIdStart, micros,
                  item.asJson.pretty(Printer.noSpaces), Some(claim.bundle))
                Snapshots += snapRow
            }.getOrElse(DBIO.successful(0))

            // Build and insert the deltas
            _ <- Deltas ++= data.zipWithIndex.toIterator
              .scanLeft[(Option[T], Option[DeltaRow])]((None, None)) {
                case ((None, _), ((_, item), i)) => (Some(item), None)
                case ((Some(prev), _), ((micros, item), i)) =>
                  val delta = fmt.diff(prev, item)
                  val deltaRow = DeltaRow(0, claim.bundle, seqIdStart + i, micros,
                    delta.asJson.pretty(Printer.noSpaces), Some(claim.bundle))
                  (Some(item), Some(deltaRow))
              }.collect {
                case (_, Some(deltaRow)) => deltaRow
              }.toSeq

            updatedCount <- (nextCursorOpt, overlapItems) match {
              // If there is a next cursor AND there are no overlapping items in the current
              // page, then we can continue backfilling.
              case (Some((nextCursor, delay)), 0) =>
                claimedBackfill
                  .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
                  .update(None, None, Some(nextCursor),
                    Some(Timestamp.from(now.plusMillis(delay.toMillis))))

              // If next cursor is None OR there are overlapping items, then the backfill
              // is complete.
              case _ =>
                log.info(s"Backfill of {} has completed: {}", path, claim)
                claimedBackfill
                  .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
                  .update(None, None, None, None)
            }

            _ = updatedCount match {
              case 1 =>
                log.debug("Updated backfill metadata for {}", claim)
              case 0 =>
                log.error("Failed to release claim {}. This may be caused by the " +
                  "backfill claim being expired due a hung page.", claim)
              case x =>
                log.warning("Released more than one ({}) claim for backfill {}.", x, claim)
            }

          } yield data
      }

    session.db.run((for {
      // Fetch the selected claim.
      claim <- claimedBackfill.result.head

      // Request the data seq, next cursor, and delay
      // (rspData: Vector[(Long, Any)], nextCursorOpt: Option[(String, FiniteDuration)])
      rspOpt <- DBIO.from(dataSource
        .backfillPage(claim.topic, path.datatype, claim.cursor)
        .transformWith {
          case Success(x) => Future.successful(Some(x))
          case Failure(_: UnsupportedOperationException) => Future.successful(None)
          case Failure(other) => Future.failed(other)
        })

      // Proceed with backfill, unless the data source threw an UnsupportedOperationException.
      // In that case, ignore and close the backfill.
      _ <- rspOpt.map(proceed(claim ,_))
        .getOrElse(for {
          x <- claimedBackfill
            .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
            .update(None, None, None, None)
          _ = log.debug(s"Ignoring unimplemented backfill for $path.")
        } yield x)

    } yield Unit).transactionally)
  }
}
