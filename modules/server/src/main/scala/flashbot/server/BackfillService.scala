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
import flashbot.server.BackfillService.BackfillTick
import flashbot.core.DeltaFmtJson
import flashbot.models.core.DataPath
import io.circe.Printer
import io.circe.syntax._

import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.blocking
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

/**
  * Any number of BackfillServices can be run in the cluster concurrently. Due to the
  * locking mechanism, they do not conflict with one another, even if they are assigned
  * to the same paths. In addition to ingesting data, this actor serves as the data
  * retention manager by deleting data past the configured retention period.
  *
  * On initialization:
  *   1. Generate a random id for this actor instance.
  *   2. Create new "unclaimed" and "incomplete" backfill record with a null cursor and
  *      set `nextPageAt` to the epoch.
  *   3. Schedule a tick every few seconds.
  *
  * On every tick:
  *   1. Claim the most recent available backfill. Available means: unclaimed and a
  *      `nextPageAt` value in the past.
  *   2. Fetch the data at the current cursor.
  *   3. Persist the data with a bundle id of `Long.MaxValue - id` and correct `seqid`s.
  *      This also lets us know if we should continue the backfill based on whether this
  *      data is past the retention period or if it overlaps with a previous backfill.
  *   4. Release the lock by unclaiming the record, and setting the `cursor` and `nextPageAt`
  *      columns according to whether the persistence function said this backfill is complete.
  */
class BackfillService[T](path: DataPath[T], dataSource: DataSource,
                         retention: Option[FiniteDuration])
                        (implicit session: SlickSession) extends Actor with ActorLogging {
  import session.profile.api._

  implicit val system = context.system
  implicit val mat = buildMaterializer()
  val random = new Random()

  implicit val fmt: DeltaFmtJson[T] = path.fmt[T]

  val instanceId = UUID.randomUUID().toString

  // If a claim is held for more than 10 seconds, something is probably wrong. Expire it.
  val ClaimTTL = 10 seconds

  // Every few seconds send a BackfillTick event to self.
  system.scheduler.schedule(0 millis, (2000 / dataSource.backfillTickRate) millis) (Future {
    blocking {
      Thread.sleep(random.nextInt(1000 / dataSource.backfillTickRate))
      self ! BackfillTick
    }
  })

  // Create a new row
  var newBackfillId = Await.result(session.db.run(
    (Backfills returning Backfills.map(_.id)) +=
      BackfillRow(0, path.source, path.topic, path.datatype, None,
        Some(Timestamp.from(Instant.EPOCH)), None, None)), 5 seconds)

  def claimedBackfill = Backfills.forPath(path).filter(_.claimedBy === instanceId)

  def availableBackfills(now: Timestamp) = Backfills
    .forPath(path)
    // Not claimed by anyone else
    .filter(_.claimedBy.isEmpty)
    .filter(_.claimedAt.isEmpty)
    // It is not complete and is not currently throttled.
    .filter(_.nextPageAt < now)
    .sortBy(_.id.desc)

  override def receive = {
    case BackfillTick =>
      val now = Instant.now()
      val nowts = Timestamp.from(now)

      // Delete data according to retention policy.
      if (retention.isDefined) {
        deleteOldData(retention.get, now) onComplete {
          case Success(0) =>
          case Success(deletedNum) =>
            log.info(s"Deleted {} items for path {} according to retention policy ({}).",
              deletedNum, path, retention.get)
          case Failure(err) =>
            log.error(err, "Failed to delete data for retention policy.")
        }
      }

      val hasClaimedOpt = session.db.run(for {
        // First, perform some global clean-up. Ensure that all stale locks are released.
        numExpired <- Backfills
          .filter(_.claimedAt < Timestamp.from(now.minusMillis(ClaimTTL.toMillis)))
          .map(bf => (bf.claimedBy, bf.claimedAt))
          .update(None, None)
        _ = { if (numExpired > 0) log.warning("Expired {} backfill claims", numExpired) }

        // Next, try to claim the next available backfill.
        claimed <- availableBackfills(nowts)
          .take(1)
          .map(bf => (bf.claimedBy, bf.claimedAt))
          .update(Some(instanceId), Some(nowts))
          .map {
            // Successful claim.
            case 1 => true
            // Unable to claim for whatever reason. Ignore.
            case 0 => false
          }
      } yield claimed)

      hasClaimedOpt.onComplete {
        case Success(true) =>
          // We just claimed a path. Let's get to work!
          runPage(now) andThen {
            case Success(data) =>
              log.debug("Backfilled data: {}", data)
            case Failure(err) =>
              log.error(err, s"An error occurred during backfill of $path")
          }
        case Success(false) => // Ignore
        case Failure(err) =>
          log.error(err, "An error occurred during backfill scheduling for {}", path)
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

    session.db.run((for {
      // Fetch the selected claim. The negative of the claim id will be the bundle id.
      claim <- claimedBackfill.result.head

      // Request the data seq, next cursor, and delay
      (rspData, nextCursorOpt) <- DBIO.from(
        dataSource.backfillPage(claim.topic, path.datatype, claim.cursor))

      // Ensure the data isn't backwards. It can be easy to mess this up.
      // The first element should be the most recent!
      data <-
        if (rspData.size >= 2 && path.datatype.ordering.compare(rspData.head._2, rspData.last._2) < 0)
          DBIO.failed(new IllegalStateException(
            s"Backfill data out of order. It must be in reverse chronological order."))
        else DBIO.successful(rspData.reverse)

      (dataStartMicros, dataEndMicros) = (data.headOption.map(_._1), data.lastOption.map(_._1))

      // Find if there is any overlap with this page and existing data. If there is,
      // still insert the data, but don't continue backfilling.
      overlapSnaps <- Snapshots.forPath(path)
        .filter(x => x.micros > dataStartMicros && x.micros <= dataEndMicros)
        .size.result
      overlapDeltas <- Deltas.forPath(path)
        .filter(x => x.micros > dataStartMicros && x.micros <= dataEndMicros)
        .size.result
      overlapItems = overlapSnaps + overlapDeltas

      _ = log.debug(s"Fetched backfill page ({} items) for path {}", data.size, path)

      // We got some data from the backfill. Let's insert it and schedule the next page.
      // Find the earliest seqid for this bundle. Only look at snapshots. There should
      // never be backfill deltas that predate the earliest snapshot.
      earliestSeqIdOpt <- Snapshots
        .filter(_.bundle === claim.bundle)
        .map(_.seqid)
        .min.result
      seqIdBound = earliestSeqIdOpt.getOrElse(0L)
      seqIdStart: Long = seqIdBound - data.size

      // Insert the snapshot
      _ <- data.headOption.map {
        case (micros, item) => Snapshots +=
          SnapshotRow(claim.bundle, seqIdStart, micros,
            item.asJson.pretty(Printer.noSpaces), Some(claim.id))
      }.getOrElse(DBIO.successful(0))

      // Build and insert the deltas
      _ <- Deltas ++= data.zipWithIndex.toIterator
        .scanLeft[(Option[T], Option[DeltaRow])]((None, None)) {
          case ((None, _), ((_, item), i)) => (Some(item), None)
          case ((Some(prev), _), ((micros, item), i)) =>
            val delta = fmt.diff(prev, item)
            val deltaRow = DeltaRow(claim.bundle, seqIdStart + i, micros,
              delta.asJson.pretty(Printer.noSpaces), Some(claim.id))
            (Some(item), Some(deltaRow))
        }.collect {
          case (_, Some(deltaRow)) => deltaRow
        }.toSeq

      _ <- (nextCursorOpt, overlapItems) match {
        // If there is a next cursor AND there are no overlapping items in the current
        // page, then we can continue backfilling.
        case (Some((nextCursor, delay)), 0) => for {
          // Update the backfill row. Release the claim. Update the cursor and delay.
          updatedNum <- claimedBackfill
            .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
            .update(None, None, Some(nextCursor),
              Some(Timestamp.from(now.plusMillis(delay.toMillis))))

          _ = updatedNum match {
            // Success
            case 1 =>
              log.debug("Updated backfill metadata")

            // This means that the claim was expired before we got a chance to update it.
            // This fails the transaction and logs an error.
            case 0 =>
              log.error("Failed to release claim {}. This may be caused by the " +
                "backfill claim being expired due a hung page.", claim)
          }
        } yield updatedNum

        // If next cursor is None OR there are overlapping items, then the backfill
        // is complete.
        case _ =>
          log.info(s"Backfill of {} has completed.", path)
          claimedBackfill
            .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
            .update(None, None, None, None)
      }

    } yield data).transactionally)
  }

  /**
    * Returns how many data items were deleted due to the retention policy.
    */
  def deleteOldData(duration: FiniteDuration, now: Instant): Future[Int] = {
    session.db.run(for {
      // Find the most recent snapshot that falls outside of the retention period.
      snap <- Snapshots
        .forPath(path)
        .sortBy(x => (x.bundle.desc, x.seqid.desc))
        .filter(x => x.micros < now.toEpochMilli * 1000 - duration.toMicros)
        .result.headOption

      // Delete all snapshots and deltas that have either a smaller bundle id than
      // the snapshot we found, or the same bundle id, but lower sequence number.
      deletedSnaps: Int <- Snapshots
        .forPath(path)
        .filter(x => x.bundle < snap.map(_.bundle) ||
          (x.bundle === snap.map(_.bundle) && x.seqid < snap.map(_.seqid)))
        .delete

      // Same with deltas.
      deletedDeltas: Int <- Deltas
        .forPath(path)
        .filter(x => x.bundle < snap.map(_.bundle) ||
            (x.bundle === snap.map(_.bundle) && x.seqid < snap.map(_.seqid)))
        .delete

    } yield deletedSnaps + deletedDeltas)
  }

}

object BackfillService {
  case object BackfillTick
}
