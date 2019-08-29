package flashbot.server
import java.time.Instant

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import flashbot.models.TimeRange
import org.scalatest.{FlatSpec, Matchers}
import flashbot.core.PriceTap

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class TimeSeriesTapTest extends FlatSpec with Matchers {
  "TimeSeriesTap" should "emit times" in {
    implicit val system = ActorSystem("test")
    implicit val mat = ActorMaterializer()
    val src: Source[Instant, NotUsed] =
      PriceTap(TimeRange.build(Instant.now(), 2 days), 5 minutes, false)
    val done: Future[Done] = src.zipWithIndex.runForeach(x => {})
    println(Await.result(done, 30 seconds))
  }

  "TimeSeriesTap" should "emit prices" in {
    implicit val system = ActorSystem("test")
    implicit val mat = ActorMaterializer()
    implicit val timeout = Timeout(30 seconds)

    val timerange = TimeRange.build(Instant.now(), 30 days)
    val src: Source[(Instant, Double), NotUsed] =
      PriceTap.akkaStream(200, .55, .35, timerange, 5 minutes)

    val done: Future[Done] = src.zipWithIndex.runForeach(x => {})
    println(Await.result(done, 30 seconds))
  }

  "TimeSeriesTap" should "emit candles" in {
    implicit val system = ActorSystem("test")
    implicit val mat = ActorMaterializer()
    val fut = PriceTap.akkaStream(1 hour)
      .via(PriceTap.aggregatePrices(12 hours))
      .throttle(1, 1 second)
      .runForeach(x => {})

//    Await.ready(fut, 1 minute)
  }
}
