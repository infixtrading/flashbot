package sources

import akka.NotUsed
import akka.actor.ActorContext
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.DataType.TradesType
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.models.core.Order.{Buy, Down, Sell, Up}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

object TestBackfillDataSource {
  val MicrosPerMinute: Long = 60L * 1000000
  val nowMicros = System.currentTimeMillis() * 1000
  val allTrades = (1 to 120) map { i =>
    Trade(i.toString, nowMicros + i * MicrosPerMinute, i, i, if (i % 2 == 0) Up else Down)
  }

  val (_historicalTrades, liveTrades) = allTrades.splitAt(85)
  // Add some overlap. The two data sets won't be evenly split in the real world.
  val historicalTrades = _historicalTrades ++ liveTrades.take(20)
}

class TestBackfillDataSource extends DataSource {
  import TestBackfillDataSource._

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = datatype match {
    case TradesType =>
      val src: Source[(Long, T), NotUsed] =
        Source(liveTrades map (t => (t.micros, t.asInstanceOf[T])))
      Future.successful(src.throttle(1, 20 millis).concat(Source.maybe))
  }

  override def backfillPage[T](topic: String, datatype: DataType[T], cursor: Option[String])
                              (implicit ctx: ActorContext, mat: ActorMaterializer) = {
    println(s"Backfill request for $topic/$datatype with cursor $cursor")
    val batchSize = 40
    val count: Int = cursor.map(_.toInt).getOrElse(0)
    val page = historicalTrades
      .dropRight(count)
      .takeRight(batchSize)
      .map(t => (t.micros, t.asInstanceOf[T]))
      .reverse
    val isDone = count + batchSize >= historicalTrades.size
    Future.successful((page, if (isDone) None else Some(((count + batchSize).toString, 100 millis))))
  }
}
