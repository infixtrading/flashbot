package flashbot.server

import java.time.Instant

import akka.actor.{Actor, ActorLogging}
import akka.stream.alpakka.slick.javadsl.SlickSession
import flashbot.util.stream._
import flashbot.db._
import flashbot.models.core.DataPath

import scala.concurrent.ExecutionContext

//import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

class RetentionService(path: DataPath[Any], retention: Option[FiniteDuration], tickRate: Int)
                      (implicit session: SlickSession) extends Actor with ActorLogging {
  import session.profile.api._

  implicit val ec: ExecutionContext = context.system.dispatcher

  case object RetentionTick

  implicit val system = context.system
  implicit val mat = buildMaterializer()
  val random = new Random()

  // Retention ticks
  system.scheduler.schedule(0 millis, (10 seconds) / tickRate)(Future {
    blocking {
      Thread.sleep(random.nextInt(5000 / tickRate))
      self ! RetentionTick
    }
  })

  override def receive = {
    case RetentionTick =>
      val now = Instant.now()

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
  }

  /**
    * Returns how many data items were deleted due to the retention policy.
    */
  def deleteOldData(duration: FiniteDuration, now: Instant): Future[Int] = {
    session.db.run(for {
      // Find the most recent snapshot that falls outside of the retention period.
      lastOutdatedSnap <- Snapshots
        .forPath(path)
        .sortBy(x => (x.bundle.desc, x.seqid.desc))
        .filter(x => x.micros < now.toEpochMilli * 1000 - duration.toMicros)
        .result.headOption

      // Delete all snapshots and deltas that have either a smaller bundle id than
      // the snapshot we found, or the same bundle id, but lower sequence number.
      // We do the inner query because delete queries may not use joins in slick.
      deletedSnaps: Int <- (Snapshots filter { snap =>
        snap.id in Snapshots
          .forPath(path)
          .filter(x => x.bundle < lastOutdatedSnap.map(_.bundle) ||
            (x.bundle === lastOutdatedSnap.map(_.bundle) &&
              x.seqid < lastOutdatedSnap.map(_.seqid)))
          .map(_.id)
      }).delete

      // Same with deltas.
      deletedDeltas: Int <- (Deltas filter { delta =>
        delta.id in Deltas
          .forPath(path)
          .filter(x => x.bundle < lastOutdatedSnap.map(_.bundle) ||
            (x.bundle === lastOutdatedSnap.map(_.bundle) &&
              x.seqid < lastOutdatedSnap.map(_.seqid)))
          .map(_.id)
      }).delete

    } yield deletedSnaps + deletedDeltas)
  }

}
