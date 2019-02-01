package flashbot.sources
import akka.actor.ActorContext
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import flashbot.core.DataType.{LadderType, TradesType}
import flashbot.core._
import flashbot.core.DataType
import flashbot.models.core.Ladder

import scala.concurrent.Future
import scala.concurrent.duration._

class TestLadderDataSource extends DataSource {

  val MicrosPerMinute: Long = 60L * 1000000

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = datatype match {
    case LadderType(depth) =>
      val nowMicros = System.currentTimeMillis() * 1000
      val seq = OrderBookTap(200)
        .map(Ladder.fromOrderBook(20))
        .zipWithIndex
        .map {
          case (ladder, i) => (nowMicros + i * MicrosPerMinute, ladder.asInstanceOf[T])
        }.toVector

      Future.successful(Source(seq).throttle(1, 50 millis))
  }
}
