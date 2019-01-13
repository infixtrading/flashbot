package sources
import akka.NotUsed
import akka.actor.ActorContext
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.DataType.TradesType
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.models.core.Order.{Buy, Sell}

import scala.concurrent.Future
import scala.concurrent.duration._

class TestDataSource extends DataSource {

  val MicrosPerMinute: Long = 60L * 1000000

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = datatype match {
    case TradesType =>
      val nowMicros = System.currentTimeMillis() * 1000
      val src: Source[(Long, T), NotUsed] = Source((1 to 60) map { i =>
        Trade(i.toString, nowMicros + i * MicrosPerMinute, i, i, if (i % 2 == 0) Buy else Sell)
      }) map (t => (t.micros, t.asInstanceOf[T]))
      Future.successful(src.throttle(1, 25 millis))
  }
}
