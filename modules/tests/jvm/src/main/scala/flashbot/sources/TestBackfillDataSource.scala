package flashbot.sources

import akka.NotUsed
import akka.actor.ActorContext
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import flashbot.core.DataType.TradesType
import flashbot.core._
import flashbot.models.Order._

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object TestBackfillDataSource {
  val MicrosPerMinute: Long = 60L * 1000000
  val nowMicros = System.currentTimeMillis() * 1000
  val allTrades = (1 to 1000) map { i =>
    Trade(i.toString, nowMicros + i * MicrosPerMinute, i, i, if (i % 2 == 0) Up else Down)
  }

  val backfillRate = 10
  val batchSize = 40

  // Find earliest snapshot that is outside of the retention period. Then delete all
  // data that comes before it. Every page will have a snapshot at the start.
  // Assumes 10 hour retention period.
  val numDeletedBatches = allTrades.takeWhile(_.micros <
    nowMicros - MicrosPerMinute * 60 * 10).size / batchSize
  val allTradesAfterRetention = allTrades.drop(numDeletedBatches * batchSize)

  val (historicalTradesA, liveTradesA) = allTrades.take(200).splitAt(150)
  val (historicalTradesB, liveTradesB) = allTrades.slice(300, 500).splitAt(150)
  val (historicalTradesC, liveTradesC) = allTrades.drop(500).splitAt(400)
  val gapTrades = allTrades.slice(200, 300)
}

class TestBackfillDataSourceA extends DataSource {
  import TestBackfillDataSource._

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = datatype match {
    case TradesType =>
      val src: Source[(Long, T), NotUsed] =
        Source(liveTradesA map (t => (t.micros, t.asInstanceOf[T])))
      Future.successful(src.throttle(1, 20 millis).concat(Source.maybe))
  }

  override def backfillPage[T](topic: String, datatype: DataType[T], cursor: Option[String])
                              (implicit ctx: ActorContext, mat: ActorMaterializer) = {
    println(s"Backfill request A for $topic/$datatype with cursor $cursor")
    val page = (historicalTradesA ++ liveTradesA.take(30))
      .toVector
      .reverse
      .dropWhile(t => cursor.isDefined && t.id.toLong >= cursor.get.toLong)
      .take(batchSize)
    val isDone = page.last.id == historicalTradesA.head.id
    Future.successful((
      page.map(t => (t.micros, t.asInstanceOf[T])),
      if (isDone) None else Some((page.last.id, 20 millis)))
    )
  }
  override protected[flashbot] def backfillTickRate: Int = TestBackfillDataSource.backfillRate
}

class TestBackfillDataSourceB extends DataSource {
  import TestBackfillDataSource._

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = datatype match {
    case TradesType =>
      val src: Source[(Long, T), NotUsed] =
        Source(liveTradesB map (t => (t.micros, t.asInstanceOf[T])))
      Future.successful(src.throttle(1, 20 millis).concat(Source.maybe))

  }

  override def backfillPage[T](topic: String, datatype: DataType[T], cursor: Option[String])
                              (implicit ctx: ActorContext, mat: ActorMaterializer)
      : Future[(Vector[(Long, T)], Some[(String, FiniteDuration)])] = {

    println(s"Backfill request B for $topic/$datatype with cursor $cursor")

    cursor match {
      case Some(id) if id == historicalTradesB.head.id =>
        return Future.failed(new RuntimeException(s"Simulating exception at ${cursor.get}"))
      case _ =>
    }

    val page = (historicalTradesB ++ liveTradesB.take(30))
      .toVector
      .reverse
      .dropWhile(t => cursor.isDefined && t.id.toLong >= cursor.get.toLong)
      .take(batchSize)
    Future.successful((
      page.map(t => (t.micros, t.asInstanceOf[T])),
      Some((page.last.id, 20 millis)))
    )
  }

  override protected[flashbot] def backfillTickRate: Int = TestBackfillDataSource.backfillRate
}
class TestBackfillDataSourceC extends DataSource {
  import TestBackfillDataSource._

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = datatype match {
    case TradesType =>
      val src: Source[(Long, T), NotUsed] =
        Source(liveTradesC map (t => (t.micros, t.asInstanceOf[T])))
      Future.successful(src.throttle(1, 20 millis).concat(Source.maybe))
  }

  override def backfillPage[T](topic: String, datatype: DataType[T], cursor: Option[String])
                              (implicit ctx: ActorContext, mat: ActorMaterializer) = {
    println(s"Backfill request C for $topic/$datatype with cursor $cursor")
    val page = allTrades
      .toVector
      .reverse
      .dropWhile(t => cursor.isDefined && t.id.toLong >= cursor.get.toLong)
      .take(batchSize)
    Future.successful((
      page.map(t => (t.micros, t.asInstanceOf[T])),
      Some((page.last.id, 20 millis)))
    )
  }

  override protected[flashbot] def backfillTickRate: Int = TestBackfillDataSource.backfillRate
}


