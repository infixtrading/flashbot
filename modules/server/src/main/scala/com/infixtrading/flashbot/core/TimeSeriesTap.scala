package com.infixtrading.flashbot.core

import java.time.Instant

import akka.NotUsed
import akka.stream.{Materializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.infixtrading.flashbot.models.core.{Candle, TimeRange}

import breeze.stats.distributions.Gaussian


import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * TimeTap is an artificial source of time series data.
  */
object TimeSeriesTap {

  def apply(timeRange: TimeRange, timeStep: FiniteDuration, isRealTime: Boolean)
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
      Source(0 to (endIndex - startIndex).toInt)
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
  def prices(initialPrice: Double, mu: Double, sigma: Double, timeRange: TimeRange,
             timeStep: Duration): Source[(Instant, Double), NotUsed] = {

    val gaussian = Gaussian(0, 1)

    def toTimestepIndex(instant: Instant): Long = instant.toEpochMilli / timeStep.toMillis

    val startIndex: Long = toTimestepIndex(timeRange.startInstant)
    val endIndex: Long = toTimestepIndex(timeRange.endInstant)
    val numTimeSteps: Long = endIndex - startIndex + 1
    val timeStepFraction: Double = 1.0/numTimeSteps

    val brownian = Source(Seq.range(0, numTimeSteps).toIndexedSeq).scan(0d) {
      case (sum: Double, i) =>
        sum + (gaussian.draw() * math.sqrt(timeStepFraction))
    }.take(numTimeSteps.toInt)

    brownian.zipWithIndex.map {
      case (w, i) =>
        val expBody = (mu - (.5 * math.pow(sigma, 2))) * (i.toDouble / numTimeSteps) + sigma * w
        (Instant.ofEpochMilli((startIndex + i) * timeStep.toMillis), initialPrice * math.exp(expBody))
    }
  }

  // Just some default parameters for when it doesn't matter
  def prices: Source[(Instant, Double), NotUsed] =
    prices(100, .5, .5, TimeRange.build(Instant.now, "now", "365d"), 1 day)

  def prices(timeStep: Duration): Source[(Instant, Double), NotUsed] =
    prices(100, .5, .5, TimeRange.build(Instant.now, "now", "365d"), timeStep)

  sealed trait FirstState
  case object NotSet extends FirstState
  case class IsSet(first: Instant, data: (Instant, Double)) extends FirstState {
    def barStart(i: Long, timeStep: Duration): Instant =
      first.plusMillis(i * timeStep.toMillis)
  }

  def aggregateCandles(timeStep: Duration): Flow[(Instant, Double), Candle, NotUsed] = {
    Flow[(Instant, Double)].statefulMapConcat { () =>
      var currentCandle: Option[Candle] = None
      (x: (Instant, Double)) => {
        val (instant, price) = x
        val singleCandle = Candle.single(instant.toEpochMilli * 1000, x._2)
        if (currentCandle.isEmpty) {
          currentCandle = Some(singleCandle)
          List()
        } else {
          val prevCandle = currentCandle.get
          currentCandle = Some(
            if (prevCandle.micros + timeStep.toMicros < x._1.toEpochMilli * 1000) singleCandle
            else prevCandle.addPrice(price))
          if (prevCandle.micros == currentCandle.get.micros) List()
          else List(prevCandle)
        }
      }
    }
  }
}
