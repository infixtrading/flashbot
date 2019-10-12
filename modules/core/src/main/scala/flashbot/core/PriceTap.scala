package flashbot.core

import java.time.Instant

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import breeze.stats.distributions.Gaussian
import flashbot.models.{Candle, TimeRange}
import flashbot.util.time._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

/**
  * TimeTap is an artificial source of time series data.
  */
object PriceTap {

  def apply(timeRange: TimeRange, timeStep: FiniteDuration, isRealTime: Boolean = false)
           (implicit mat: Materializer): Source[Instant, NotUsed] = {
    val startAt = Instant.ofEpochMilli(timeRange.start/1000)
    def toTimestepIndex(instant: Instant): Long = instant.toEpochMilli / timeStep.toMillis

    val startIndex: Long = toTimestepIndex(timeRange.startInstant)
    val endIndex: Long = toTimestepIndex(timeRange.endInstant)

    val incrementingSrc: Source[Int, NotUsed] = if (isRealTime) {
      val (ref, src) = Source.actorRef[Int](Int.MaxValue, OverflowStrategy.fail).preMaterialize()
      val (cancel, tickSrc) = Source.tick(0 seconds, timeStep, "")
        .zipWithIndex.map(_._2).preMaterialize()
      tickSrc.to(Sink.foreach(ref ! _))
      src.watchTermination() {
        case (_, fut) => fut.onComplete(_ => cancel.cancel())
      }
      src
    } else {
      Source(0 until (endIndex - startIndex).toInt)
    }
    incrementingSrc.map(i => startAt.plusMillis(i.toLong * timeStep.toMillis))
  }

  /**
    * Brownian motion stream source - https://jtsulliv.github.io/stock-movement/
    *
    * Implements the typical model for stock price dynamics using the
    * following stochastic differential equation:
    *
    *     dS = mu*S*dt + sigma*S*dWt
    *
    *     Where `S` is the stock price, `mu` is the drift coefficient,
    *     `sigma` is the diffusion coefficient, and dWt is the Brownian
    *     motion.
    *
    * The drift coefficient is the mean of returns for some time period.
    * The diffusion coefficient is the standard deviation of those same returns.
    *
    * The closed form solution of the GBM is:
    *
    *     S(t) = S(0) * e**((mu - 1/2 * sigma**2)*t + sigma*W(t))
    *
    * @param initialPrice
    * @param mu
    * @param sigma
    * @return
    */
  def akkaStream(initialPrice: Double, mu: Double, sigma: Double, timeRange: TimeRange,
                 timeStep: FiniteDuration, infinite: Boolean = false): Source[(Instant, Double), NotUsed] =
    Source.fromIterator(() => iterator(initialPrice, mu, sigma, timeRange, timeStep, infinite))


  def iterator(initialPrice: Double, mu: Double, sigma: Double, timeRange: TimeRange,
               timeStep: FiniteDuration, infinite: Boolean = false): Iterator[(Instant, Double)] = {
    val gaussian = Gaussian(0, 1)
    val startIndex = timeStep.javaDuration.timeStepIndexOfMicros(timeRange.start)
    val endIndex = timeStep.javaDuration.timeStepIndexOfMicros(timeRange.end)

    val numTimeSteps: Long = endIndex - startIndex
    val timeStepFraction: Double = 1.0/numTimeSteps

    val brownian = Iterator.from(0).scanLeft(0d) {
      case (sum: Double, _) =>
        sum + (gaussian.draw() * math.sqrt(timeStepFraction))
    }

    (if (infinite) brownian else brownian.take(numTimeSteps.toInt))
      .zipWithIndex.map {
        case (w, i) =>
          val expBody = (mu - (.5 * math.pow(sigma, 2))) * (i.toDouble / numTimeSteps) + sigma * w
          (Instant.ofEpochMilli((startIndex + i) * timeStep.toMillis), initialPrice * math.exp(expBody))
      }
  }

  // Just some default parameters for when it doesn't matter
  def akkaStream: Source[(Instant, Double), NotUsed] =
    akkaStream(100, .5, .5, TimeRange.build(Instant.now, "now", "365d"), 1 day)

  def akkaStream(timeStep: FiniteDuration): Source[(Instant, Double), NotUsed] =
    akkaStream(100, .5, .5, TimeRange.build(Instant.now, "now", "365d"), timeStep)

  sealed trait FirstState
  case object NotSet extends FirstState
  case class IsSet(first: Instant, data: (Instant, Double)) extends FirstState {
    def barStart(i: Long, timeStep: Duration): Instant =
      first.plusMillis(i * timeStep.toMillis)
  }


  // Drop the first candle by default because it's not likely that it will be complete.
  // However, it's useful to keep the first candle for testing purposes.
  def tradeAggregator(timeStep: FiniteDuration): ((Instant, Double, Double)) => Iterable[Candle] =
    getFlattener[(Instant, Double, Double)](
      (micros, x) => Candle.single(micros, x._2, x._3),
      (memo, x) => memo.mergeOHLC(x._2, x._2, x._2, x._2, x._3),
      _._1)(timeStep)

  def priceAggregator(timeStep: FiniteDuration): ((Instant, Double)) => Iterable[Candle] =
    getFlattener[(Instant, Double)](
      (micros, x) => Candle.single(micros, x._2),
      (memo, x) => memo.mergeOHLC(x._2, x._2, x._2, x._2, 0d),
      _._1)(timeStep)

  def candleAggregator(timeStep: FiniteDuration): Candle => Iterable[Candle] =
    getFlattener[Candle](
      (micros, x) => x.copy(micros = micros),
      (memo, x) => memo.mergeOHLC(x.open, x.high, x.low, x.close, x.volume),
      _.instant)(timeStep)

  def aggregateTradesFlow(timeStep: FiniteDuration,
                          keepFirstCandle: Boolean = false): Flow[(Instant, Double, Double), Candle, NotUsed] =
    getAggregatorFlow[(Instant, Double, Double)](
        (micros, x) => Candle.single(micros, x._2, x._3),
        (memo, x) => memo.mergeOHLC(x._2, x._2, x._2, x._2, x._3),
        _._1)(timeStep)
      .drop(if (keepFirstCandle) 0 else 1)


  def aggregatePricesFlow(timeStep: FiniteDuration,
                          keepFirstCandle: Boolean = false): Flow[(Instant, Double), Candle, NotUsed] =
    Flow[(Instant, Double)]
      .map(x => (x._1, x._2, 0d))
      .via(aggregateTradesFlow(timeStep, keepFirstCandle))


  def aggregateCandlesFlow(timeStep: FiniteDuration): Flow[Candle, Candle, NotUsed] =
    getAggregatorFlow[Candle](
      (micros, candle) => candle.copy(micros = micros),
      (memo, candle) => memo.mergeOHLC(candle.open, candle.high,
        candle.low, candle.close, candle.volume),
      _.instant)(timeStep)

  private def getFlattener[T](build: (Long, T) => Candle,
                              reduce: (Candle, T) => Candle,
                              inst: T => Instant)
                             (timeStep: java.time.Duration)
      : T => Iterable[Candle] = {
    var currentCandle: Option[Candle] = None
    val empty = List.empty

    item => {
      val timeStepStartMicros = timeStep.normalizeTimeStepMicros(inst(item).micros)
      lazy val singleCandle = build(timeStepStartMicros, item)

      if (currentCandle.isEmpty) {
        currentCandle = Some(singleCandle)
        empty
      } else {
        val prevCandle = currentCandle.get
        currentCandle = Some(
          if (prevCandle.micros + timeStep.toMicros <= timeStepStartMicros)
            singleCandle
          else reduce(prevCandle, item))
        if (prevCandle.micros == currentCandle.get.micros) empty
        else List(prevCandle)
      }
    }
  }

  private def getAggregatorFlow[T](build: (Long, T) => Candle,
                                   reduce: (Candle, T) => Candle,
                                   inst: T => Instant)
                                  (timeStep: java.time.Duration): Flow[T, Candle, NotUsed] =
    Flow[T].statefulMapConcat{() =>
      val fn = getFlattener[T](build, reduce, inst)(timeStep)
      fn(_).toList
    }

//  private def _aggAsCandles[T](build: T => Candle, reduce: (Candle, T) => Candle, inst: T => Instant)
//                              (timeStep: java.time.Duration): Flow[T, Candle, NotUsed] =
//    Flow[T].statefulMapConcat { () =>
//      var currentCandle: Option[Candle] = None
//      var lastSeenMicros: Long = -1
//      (x: T) => {
//        val timeStepStartMicros = timeStep.normalizeTimeStepMicros(inst(x).micros)
//        val singleCandle = build(x).copy(micros = timeStepStartMicros)
//
//        if (lastSeenMicros != -1 && lastSeenMicros > singleCandle.micros) {
//          println("Out of order data?")
//        }
//        if (lastSeenMicros != -1 && lastSeenMicros == singleCandle.micros) {
//          println("Duplicate order data?")
//        }
//        lastSeenMicros = singleCandle.micros
//
//        if (currentCandle.isEmpty) {
//          currentCandle = Some(singleCandle)
//          if (currentCandle.get.volume > 2000) {
//            println("ERROR?", currentCandle)
//          }
//          List()
//        } else {
//          val prevCandle = currentCandle.get
//          println(prevCandle.micros, timeStep.toMicros, prevCandle.micros + timeStep.toMicros,
//            timeStepStartMicros, prevCandle.micros + timeStep.toMicros <= timeStepStartMicros)
//          currentCandle = Some(
//            if (prevCandle.micros + timeStep.toMicros <= timeStepStartMicros) singleCandle
//            else reduce(prevCandle, x))
//          if (currentCandle.get.volume > 2000) {
//            println("ERROR?", currentCandle)
//          }
//          if (prevCandle.micros == currentCandle.get.micros) List()
//          else List(prevCandle)
//        }
//      }
//    }

}
