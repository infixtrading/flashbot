package flashbot.util

import java.time
import java.time.{Instant, ZoneId, ZoneOffset}
import java.util.function

import cats.{Foldable, Monoid}
import flashbot.core.Timestamped.HasTime
import flashbot.models.Candle
import org.ta4j.core.{Bar, BaseBar, BaseTimeSeries, Indicator, TimeSeries}
import org.ta4j.core.BaseTimeSeries.SeriesBuilder
import org.ta4j.core.num.{DoubleNum, Num}
import flashbot.util.time._
import flashbot.util.timeseries.Implicits._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

package object timeseries {

  protected[timeseries] class SeriesConfig(val failOnOldData: Boolean,
                                           val maxBarCount: Int) {
    // Implementation details for bookkeeping of candles
    protected[timeseries] var baseInterval: Long = -1
    protected[timeseries] var lastCandleSeenAt: Long = -1
    protected[timeseries] var firstCandleBuffer: Option[Candle] = None
    protected[timeseries] var inferredCandleInterval: Long = -1

    def interval: java.time.Duration = intervalMicros micros
    def intervalMicros: Long = {
      if (baseInterval != -1) baseInterval
      else if (inferredCandleInterval != -1) inferredCandleInterval
      else throw new RuntimeException("This time series is not configured with a bar interval.")
    }

    def hasInterval: Boolean = baseInterval != -1 || inferredCandleInterval != -1

    def withInterval(newInterval: java.time.Duration): Unit = {
      val newBaseMicros = newInterval.toMicros
      SeriesConfig.validateIntervals(newBaseMicros, inferredCandleInterval)
      baseInterval = newBaseMicros
    }

    protected[timeseries] def registerCandle(micros: Long, open: Double, high: Double,
                                             low: Double, close: Double, volume: Double): Option[Candle] = {
      if (volume > 1700) {
        println("err")
      }
      var ejectedBufferedCandle: Option[Candle] = None
      if (lastCandleSeenAt == -1) {
        firstCandleBuffer = Some(Candle(micros, open, high, low, close, volume))
      } else {
        val inferred = micros - lastCandleSeenAt
//        println(inferred.micros.toCoarsest,
//          micros, micros.microsToZdtLocal)

        // Make sure that all candles are evenly spaced. A.k.a. the inferred interval should
        // always be constant from candle to candle.
        if (inferredCandleInterval != -1 && inferred != inferredCandleInterval)
          throw new RuntimeException(
            s"""
              |The inferred interval from the latest candles, ${inferred.micros.toCoarsest},
              |does not match the previously inferred interval of ${inferredCandleInterval.micros.toCoarsest}.
              |""".stripMargin)

        // Also validate against the base interval.
        SeriesConfig.validateIntervals(baseInterval, inferred)

        // Then set the inferred
        inferredCandleInterval = inferred

        ejectedBufferedCandle = firstCandleBuffer
        firstCandleBuffer = None
      }
      lastCandleSeenAt = micros
      ejectedBufferedCandle
    }
  }

  object SeriesConfig {
    def apply(failOnOldData: Boolean = false,
              maxBarCount: Int = 10000): SeriesConfig =
      new SeriesConfig(failOnOldData, maxBarCount)

    private def validateIntervals(base: Long, inferred: Long): Unit = {
      if (inferred != -1 && base != -1 && base < inferred) {
        throw new RuntimeException(
          s"""Base time series interval of ${base.micros.toCoarsest} must be larger than
             |the interval of ${inferred.micros.toCoarsest} which was inferred from candles.""".stripMargin)
      }
    }
  }

  object Implicits {
    implicit class TimeSeriesOps(series: TimeSeries) {
      // This must not use `synchronized`, otherwise there will be a deadlock
      // in `updateConfig`
      def config: SeriesConfig = seriesConfigs(series)
      def getPreviousBar: Bar = series.getBar(series.getEndIndex - 1)
      def withInterval(interval: java.time.Duration): TimeSeries = {
        series.config.withInterval(interval)
        series
      }
      def interval: java.time.Duration = series.config.interval
      def hasInterval: Boolean = series.config.hasInterval

      def put(candle: Candle): TimeSeries = putCandle(series, candle)

      def iterator: Iterator[Bar] = new Iterator[Bar] {
        private var i = series.getBeginIndex
        override def hasNext: Boolean = i <= series.getEndIndex
        override def next(): Bar = {
          val bar = series.getBar(i)
          i += 1
          bar
        }
      }
    }

    implicit class BarOps(bar: Bar) {
      def open: Double = bar.getOpenPrice.getDelegate.doubleValue()
      def high: Double = bar.getMaxPrice.getDelegate.doubleValue()
      def low: Double = bar.getMinPrice.getDelegate.doubleValue()
      def close: Double = bar.getClosePrice.getDelegate.doubleValue()
      def volume: Double = bar.getVolume.getDelegate.doubleValue()
      def micros: Long = bar.getBeginTime.millis * 1000
      def candle: Candle = Candle(micros, open, high, low, close, volume)
    }

    implicit class StringOps(name: String) {
      def timeSeries: TimeSeries = buildTimeSeries(name)
      def timeSeries(config: SeriesConfig): TimeSeries = buildTimeSeries(name, config)
    }

    implicit class IndicatorOps[I <: Indicator[_]](indicator: I) {

    }
  }

  def buildTimeSeries(name: String): TimeSeries = buildTimeSeries(name, SeriesConfig())

  def buildTimeSeries(name: String, config: SeriesConfig): TimeSeries = {
    val ts = new BaseTimeSeries.SeriesBuilder()
      .withName(name)
      .withMaxBarCount(config.maxBarCount)
      .withNumTypeOf(DoubleNum.valueOf(_))
      .build()
    putConfig(ts, config)
    ts
  }

  def putCandle(series: TimeSeries, candle: Candle): TimeSeries =
    putOHLCV(series, candle.micros, candle.open, candle.high, candle.low, candle.close, candle.volume)

  private def putCandle(series: TimeSeries, candle: Candle, shouldRegister: Boolean): TimeSeries =
    putOHLCV(series, candle.micros, candle.open, candle.high, candle.low,
      candle.close, candle.volume, shouldRegister)

  def putOHLCV(series: TimeSeries, micros: Long, open: Double, high: Double, low: Double,
               close: Double, volume: Double): TimeSeries =
    putOHLCV(series, micros, open, high, low, close, volume, shouldRegister = true)

  private def putOHLCV(series: TimeSeries, micros: Long, open: Double, high: Double, low: Double,
                       close: Double, volume: Double, shouldRegister: Boolean): TimeSeries = {
    if (shouldRegister) {
      // Register the incoming candle with the config. Iff this is the second candle to register,
      // the function will eject the first candle, which should be placed into the series before
      // the current one. The ejected one should not register again.
      val ejectedBufferedCandle = series.config.registerCandle(micros, open, high, low, close, volume)
      if (ejectedBufferedCandle.isDefined)
        putCandle(series, ejectedBufferedCandle.get, shouldRegister = false)
    }

    if (series.hasInterval) {
      put(series, micros, (_, isNewBar) => {
        val curBar = series.getLastBar
        val fn = series.function
        val newOpen = if (isNewBar) fn(open) else curBar.getOpenPrice
        val newHigh = if (isNewBar) fn(high) else curBar.getMaxPrice.max(fn(high))
        val newLow = if (isNewBar) fn(low) else curBar.getMinPrice.min(fn(low))
        val newClose = fn(close)
        val newVolume = curBar.getVolume.plus(fn(volume))
        val newBar = new BaseBar(curBar.getTimePeriod, curBar.getEndTime,
          newOpen, newHigh, newLow, newClose, newVolume, fn(0))
        series.addBar(newBar, true)
      })
    }

    series
  }

  private def put(series: TimeSeries, micros: Long,
                  updateLastBar: (TimeSeries, Boolean) => Unit): TimeSeries = {
    val config = series.config
    val intervalMicros = config.intervalMicros
    if (intervalMicros % 1000 != 0)
      throw new RuntimeException("TimeSeries intervals must have millisecond granularity")
    val intervalMillis = config.intervalMicros / 1000
    val globalIndex = micros / (intervalMillis * 1000)
    val alignedMillis = globalIndex * intervalMillis
    val zdt = Instant.ofEpochMilli(alignedMillis).zdt

    // If the data is outdated, then either throw or ignore and return immediately.
    if (series.getBarCount > 0 && series.getLastBar.getBeginTime.isAfter(zdt)) {
      if (config.failOnOldData)
        throw new RuntimeException("""This time series does not support outdated data.""")
      else
        return series
    }

    // Until the last bar exists and accepts the current time, create a new bar.
    var addedBars: Int = 0
    while (series.getBarCount == 0 || !series.getLastBar.inPeriod(zdt)) {
      val lastBar: Option[Bar] = if (series.getBarCount == 0) None else Some(series.getLastBar)
      val startingTime = if (lastBar.isEmpty) zdt else lastBar.get.getEndTime

      // But, before we add the new bar, we make sure the last one isn't empty. If it empty,
      // copy over the close data from the one before it so that calculations aren't messed up.
      if (lastBar.isDefined && addedBars > 0) {
        // The second to last bar should always exist if an empty last bar exists.
        // Furthermore, it should never be empty.
        lastBar.get.addPrice(series.getPreviousBar.getClosePrice)
      }

      // Ok, now we can add the new bar.
      val interval = config.interval
      series.addBar(interval, startingTime.plus(interval))
      addedBars = addedBars + 1
    }

    updateLastBar(series, addedBars > 0)

    series
  }

  private val seriesConfigs = mutable.WeakHashMap.empty[TimeSeries, SeriesConfig]
  private def putConfig(series: TimeSeries, config: SeriesConfig): TimeSeries = {
    seriesConfigs.synchronized {
      seriesConfigs(series) = config
    }
    series
  }
  private def updateConfig(series: TimeSeries, updateFn: SeriesConfig => SeriesConfig): TimeSeries = {
    seriesConfigs.synchronized {
      seriesConfigs(series) = updateFn(series.config)
    }
    series
  }

  object Scannable {

    // Type classes
    trait GenEmpty[C] {
      def empty(micros: Long, prev: Option[C]): C
    }

    trait Reducible[C] extends Foldable[C, C]
    trait Foldable[I, C] {
      def fold(candle: C, item: I): C
    }

    trait Scannable[C] extends Reducible[C] with GenEmpty[C]
    trait ScannableItem[I, C] extends Foldable[I, C] with GenEmpty[C]


    // Implicits
    implicit def scannableItem[I, C](implicit foldableItem: Foldable[I, C],
                                     genEmptyBar: GenEmpty[C]): ScannableItem[I, C] =
      new ScannableItem[I, C] {
        override def fold(candle: C, item: I): C = foldableItem.fold(candle, item)
        override def empty(micros: Long, prev: Option[C]): C = genEmptyBar.empty(micros, prev)
      }

    implicit def scannable[C](implicit reducible: Reducible[C],
                              genEmpty: GenEmpty[C]): Scannable[C] = new Scannable[C] {
      override def fold(candle: C, item: C): C = reducible.fold(candle, item)
      override def empty(micros: Long, prev: Option[C]): C = genEmpty.empty(micros, prev)
    }

    implicit def candleIsReducible: Reducible[Candle] = (c: Candle, i: Candle) => c.mergeOHLC(i)

    // Keep the previous bars prices, but overwrite the time to our own, and remove volume,
    // if the previous bar exists.
    implicit def candleHasEmpty: GenEmpty[Candle] =
      (micros: Long, prev: Option[Candle]) =>
        prev.map(_.copy(micros = micros, volume = 0))
          .getOrElse(Candle.empty(micros))


    type PriceSeries = (Instant, Double)
    implicit def priceSeriesFoldIntoCandle: Foldable[PriceSeries, Candle] =
      (c: Candle, i: PriceSeries) => c.mergePrice(i._2)

    type PriceVolSeries = (Instant, Double, Double)
    implicit def priceVolSeriesFoldIntoCandle: Foldable[PriceVolSeries, Candle] =
      (c: Candle, i: PriceVolSeries) => c.mergeTrade(i._2, i._3)

  }

  import Scannable._


  def scan[V:HasTime, C:HasTime](timeStep: java.time.Duration)
                                (implicit scannable: Scannable[V]): Iterable[C] = {
    new Iterator[C] {
      override def hasNext: Boolean = ???

      override def next(): C = ???
    }.toIterable
  }


}
