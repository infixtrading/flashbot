package flashbot.server
import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.util.Timeout
import flashbot.models.{Candle, TimeRange}
import org.scalatest.{FlatSpec, Matchers}
import flashbot.core.PriceTap
import flashbot.util.time._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

class PriceTapTest extends FlatSpec with Matchers {
  "PriceTap" should "emit times" in {
    implicit val system = ActorSystem("test")
    implicit val mat = ActorMaterializer()
    val timeRange = TimeRange.build(Instant.now(), 2 days)
    val timeStep =  5 minutes
    val src: Source[Instant, NotUsed] =
      PriceTap(timeRange, timeStep)
    var lastInstant: Option[Instant] = None
    var count = 0
    val done: Future[Done] = src.zipWithIndex.runForeach(x => {
      if (lastInstant.isDefined) {
        (x._1.micros - lastInstant.get.micros) shouldEqual timeStep.toMicros
      }
      lastInstant = Some(x._1)
      count += 1
    })
    println(Await.result(done, 30 seconds))
    count shouldEqual 2 * 24 * 12
  }

  "PriceTap" should "emit prices" in {
    val timerange = TimeRange.build(Instant.now(), 30 days)
    val timeStep = 5 minutes
    val it = PriceTap.iterator(200, .55, .35, timerange, timeStep)
    var count = 0
    it.zipWithIndex.foreach(x => {
      count += 1
    })
    count shouldEqual 30 * 24 * 12
  }

  "PriceTap" should "aggregate prices into candles" in {
    val timerange = TimeRange.build(Instant.now(), 30 days)
    val timeStep = (5 minutes).javaDuration
    val it = PriceTap.iterator(200, .55, .35, timerange, timeStep)

    val srcArray: Array[(Instant, Double)] = it.toArray
    srcArray.length shouldEqual 30 * 24 * 12
    srcArray.head._1.micros shouldEqual timeStep.normalizeTimeStepMicros(timerange.start)
    srcArray.last._1.micros shouldEqual timeStep.normalizeTimeStepMicros(timerange.end) - timeStep.toMicros

    // Aggregate 5 min prices into 10 min candles
    val tenMin = (10 minutes).javaDuration
    val tenMinCandles: Array[Candle] = srcArray.toIterable
      .flatMap(PriceTap.priceAggregator(tenMin))
      .toArray
    tenMinCandles.length shouldEqual 30 * 24 * 6
    tenMinCandles.head.micros shouldEqual tenMin.normalizeTimeStepMicros(timerange.start)
    tenMinCandles.last.micros shouldEqual tenMin.normalizeTimeStepMicros(timerange.end) - tenMin.toMicros

    // Aggregate the 10 min candles into 1 hour candles
    val hour = (1 hour).javaDuration
    val hourCandles: Array[Candle] = tenMinCandles.toIterable
      .flatMap(PriceTap.candleAggregator(hour))
      .toArray
    hourCandles.length shouldEqual 30 * 24
    hourCandles.head.micros shouldEqual hour.normalizeTimeStepMicros(timerange.start)
    hourCandles.last.micros shouldEqual hour.normalizeTimeStepMicros(timerange.end) - hour.toMicros



//    implicit val system = ActorSystem("test")
//    implicit val mat = ActorMaterializer()
//    val fut = PriceTap.akkaStream(1 hour)
//      .via(PriceTap.aggregatePricesFlow(12 hours))
//      .throttle(1, 1 second)
//      .runForeach(x => {
//        println(x)
//      })

//    Await.ready(fut, 1 minute)
  }
}
