package com.infixtrading.flashbot.engine

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

import akka.actor.{Actor, ActorLogging}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.slick.javadsl.SlickSession
import com.infixtrading.flashbot.core.DataSource
import com.infixtrading.flashbot.db._
import com.infixtrading.flashbot.engine.BackfillService.BackfillTick
import com.infixtrading.flashbot.models.core.DataPath
import io.circe.Printer
import io.circe.syntax._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

/**
  * Any number of BackfillServices can be run in the cluster concurrently. Due to the locking
  * mechanism, they do not conflict with one another, even if they are assigned to the same paths.
  *
  * Every ~1s, this actor sends itself a BackfillTick which triggers a few operations. First it
  * cleans up stale locks to ensure backfills can't series stuck for unforseen reasons. Then it tries
  * to claim an available backfill. If the claim is successful, then it's this actor's duty to:
  *
  *   1. Fetch the data at the current cursor.
  *   2. Persist the data with a bundle id of `0` and correct `seqid`s.
  *   3. Release the lock.
  *   4. Schedule the next backfill page by updating the cursor and next_backfill_at columns.
  */
class BackfillService(session: SlickSession, path: DataPath,
                      dataSource: DataSource) extends Actor with ActorLogging {
  import session.profile.api._

  val system = context.system
  val random = new Random()
  implicit val mat = ActorMaterializer()

  val instanceId = UUID.randomUUID().toString

  // If a claim is held for more than 10 seconds, something is probably wrong. Expire it.
  val ClaimTTL = 10 seconds

  // Every 500-1500 ms we send a process a BackfillTick event.
  system.scheduler.schedule(0 millis, 500 millis) (Future {
    Thread.sleep(random.nextInt(1000))
    self ! BackfillTick
  })

  def selectBackfill = Backfills.filter(row => row.source === path.source &&
    row.topic === path.topic && row.datatype === path.datatype)

  def selectClaimed = selectBackfill.filter(_.claimedBy === instanceId)

  override def receive = {

    /**
      * In order to know what action to take for the given path, we need to try to create a new
      * backfill record with us as the claimer. If the insert succeeds, then we have claimed a new
      * row and need to series to work.
      *
      * However if the record already does exist, which we'll know from a unique key violation
      * during insert, then we need to try to claim it, which we can only do if no one else holds
      * the claim.
      */
    case BackfillTick =>
      val now = Instant.now()
      val nowts = Timestamp.from(now)

      val hasClaimedOpt = session.db.run(for {
        // First, perform some global clean-up. Ensure that all stale locks are released.
        numExpired <- Backfills
          .filter(_.claimedAt < Timestamp.from(now.minusMillis(ClaimTTL.toMillis)))
          .map(bf => (bf.claimedBy, bf.claimedAt))
          .update(None, None)
        _ = { if (numExpired > 0) log.warning("Expired {} backfill claims", numExpired) }

        // Create new row and try to insert it.
        newRow = BackfillRow(0, path.source, path.topic, path.datatype, None,
          Some(Timestamp.from(Instant.EPOCH)), Some(instanceId), Some(nowts))
        numInsertedTry <- (Backfills += newRow).asTry
        claimed <- numInsertedTry match {
          // Successful insert.
          case Success(1) =>
            DBIO.successful(true)

          case Success(0) =>
            val err = new RuntimeException("Programmer error")
            log.error(err, "This is not supposed to happen")
            throw err

          // Failed insert. Try to claim existing.
          case Failure(err) =>
            selectBackfill
              // Not claimed by anyone else
              .filter(_.claimedBy.isEmpty)
              // Backfill is not complete eyt
              .filter(_.cursor.isDefined)
              // It is not currently throttled
              .filter(_.nextPageAt < nowts)
              // Claim it
              .map(bf => (bf.claimedBy, bf.claimedAt))
              .update(Some(instanceId), Some(nowts))
              .map {
                // Successful claim.
                case 1 => true
                // Unable to claim for whatever reason. Ignore.
                case 0 => false
              }
        }
      } yield claimed)

      hasClaimedOpt.onComplete {
        case Success(true) =>
          // We just claimed a path. Let's series to work!
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
  def runPage[T](now: Instant) = {
    val fmt = path.fmt[T]
    implicit val itemEn = fmt.modelEn
    implicit val deltaEn = fmt.deltaEn

    log.debug("Running backfill page for {}", path)

    session.db.run((for {
      // Fetch the selected claim. The negative of the claim id will be the bundle id.
      claim <- selectClaimed.result.head

      // Request the data seq, next cursor, and delay
      (data, nextCursorOpt) <- DBIO.from(
        dataSource.backfillPage(claim.topic, path.dataTypeInstance[T], claim.cursor))

      // We got some data from the backfill. Let's insert it and schedule the next page.
      // Find the earliest seqid for this bundle. Only look at snapshots. There should
      // never be backfill deltas that predate the earliest snapshot.
      earliestSeqIdOpt <- Snapshots
        .filter(_.bundle === claim.bundle)
        .map(_.seqid)
        .result.headOption
      seqIdBound = earliestSeqIdOpt.getOrElse(0L)
      seqIdStart: Long = seqIdBound - data.size

      // Insert the snapshot
      _ <- data.headOption.map {
        case (micros, item) => Snapshots +=
          SnapshotRow(claim.bundle, seqIdStart, micros,
            item.asJson.pretty(Printer.noSpaces))
      }.getOrElse(DBIO.successful(0))

      // Build and insert the deltas
      _ <- Deltas ++= data.zipWithIndex.toIterator
        .scanLeft[(Option[T], Option[DeltaRow])]((None, None)) {
          case ((None, _), ((_, item), i)) => (Some(item), None)
          case ((Some(prev), _), ((micros, item), i)) =>
            val delta = fmt.diff(prev, item)
            val deltaRow = DeltaRow(claim.bundle, seqIdStart + i, micros,
              delta.asJson.pretty(Printer.noSpaces))
            (Some(item), Some(deltaRow))
        }.collect {
          case (_, Some(deltaRow)) => deltaRow
        }.toSeq

      rowsUpdated <- nextCursorOpt match {
        // Base case. If page result is None, then the backfill is complete.
        case None =>
          log.info(s"Backfill of $path has completed.")
          selectClaimed
            .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
            .update(None, None, None, None)

        case Some((nextCursor, delay)) => for {
          // Update the backfill row. Release the claim. Update the cursor and delay.
          updatedNum <- selectClaimed
            .map(bf => (bf.claimedBy, bf.claimedAt, bf.cursor, bf.nextPageAt))
            .update(None, None, Some(nextCursor),
              Some(Timestamp.from(now.plusMillis(delay.toMillis))))

          _ = updatedNum match {
            // Success
            case 1 =>

            // This means that the claim was expired before we got a chance to update it.
            // This fails the transaction and logs an error.
            case 0 =>
              log.error("Failed to release claim {}. This may be caused by the " +
                "backfill claim being expired due a hung page.", claim)
          }
        } yield updatedNum

      }

    } yield data).transactionally)
  }

}

object BackfillService {
  case object BackfillTick
}
