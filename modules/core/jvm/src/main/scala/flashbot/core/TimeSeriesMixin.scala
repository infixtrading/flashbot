package flashbot.core

import java.time.{Instant, ZoneOffset, ZonedDateTime}

import flashbot.core.ReportEvent.{CandleAdd, CandleUpdate}
import flashbot.models.core.{Candle, Market, FixedSize}
import flashbot.util.time._
import org.ta4j.core.{Bar, BaseBar, BaseTimeSeries, TimeSeries}
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.Num

import scala.concurrent.duration.FiniteDuration

trait TimeSeriesMixin { self: Strategy[_] =>

  def barSize: FiniteDuration = self.sessionBarSize

  // The number of bars in the duration
  def barCount(duration: FiniteDuration): Int = (duration.toMicros / barSize.toMicros).toInt

  var allSeries: Map[String, TimeSeries] = Map.empty

  var closePrices: Map[String, ClosePriceIndicator] = Map.empty

  def getPrice(market: Market): FixedSize = {
    val key = priceKey(market.exchange, market.symbol)
    val indicator = closePrices.getOrElse(key, new ClosePriceIndicator(allSeries(key)))
    val price = indicator.getValue(indicator.getTimeSeries.getEndIndex).doubleValue()
    (price, market.symbol)
  }

  case class PriceIndicator(market: Market) extends Indicator[Double] {
    override def minBars = 0
    override def calculate = getPrice(market).num
    override def name = ???
    override def parse(str: String, indicatorIndex: Map[String, Indicator[_]]) = ???
  }

  private def getGlobalIndex(micros: Long): Long = micros / (barSize.toMillis * 1000)

  def hasNonZeroClosePrice(bar: Bar): Boolean = {
    var ret = false
    try {
      val fn = bar.getClosePrice.function
      val zero = fn(0)
      val close: Num = bar.getClosePrice
      if (close.isGreaterThan(zero)) {
        ret = true
      }
    } catch {
      case err =>
//        println("Unable to fetch bar close price. Inferring that it's empty.", err)
    }
    ret
  }

  def barToCandle(bar: Bar): Candle = {
    val micros = bar.getBeginTime.toInstant.toEpochMilli * 1000
    Candle(micros,
      bar.getOpenPrice.getDelegate.doubleValue(),
      bar.getMaxPrice.getDelegate.doubleValue(),
      bar.getMinPrice.getDelegate.doubleValue(),
      bar.getClosePrice.getDelegate.doubleValue(),
      bar.getVolume.getDelegate.doubleValue())
  }

  def recordIndicator(key: String, micros: Long, value: Double)(implicit ctx: TradingSession): Unit =
    _record(indicatorKey(key), micros, value, None)

  def recordTrade(market: Market,
                  micros: Long,
                  price: Double,
                  amount: Option[Double] = None)
                 (implicit ctx: TradingSession): Unit = {
    _record(priceKey(market.exchange, market.symbol), micros, price, amount)
  }

  def recordTrade(exchange: String,
                  product: String,
                  micros: Long,
                  price: Double,
                  amount: Option[Double] = None)
                 (implicit ctx: TradingSession): Unit = {
    _record(priceKey(exchange, product), micros, price, amount)
  }

  private def _record(key: String, micros: Long, price: Double, amount: Option[Double])
                     (implicit ctx: TradingSession): Unit = {
    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key ->
        new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build())
    }
    val series = allSeries(key)

    val alignedMillis = getGlobalIndex(micros) * barSize.toMillis
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(alignedMillis), ZoneOffset.UTC)

    var addedNewBar = false

    // Until the last bar exists and accepts the current time, create a new bar.
    while (series.getBarCount == 0 || !series.getLastBar.inPeriod(zdt)) {
      val lastBar: Option[Bar] = if (series.getBarCount == 0) None else Some(series.getLastBar)
      val startingTime = if (lastBar.isEmpty) zdt else lastBar.get.getEndTime
      // But, before we add the new bar, we make sure the last one isn't empty. If it empty,
      // copy over the close data from the one before it so that calculations aren't messed up.
      if (lastBar.isDefined && !hasNonZeroClosePrice(lastBar.get)) {
        // The second to last bar should always exist if an empty last bar exists. Furthermore,
        // it should never be empty.
        val secondToLastBar = series.getBar(series.getEndIndex - 1)
        lastBar.get.addPrice(secondToLastBar.getClosePrice)
        ctx.send(CandleUpdate(key, barToCandle(series.getLastBar)))
      }
      // Ok, now we can add the new bar.
      addedNewBar = true
      series.addBar(barSize, startingTime.plus(barSize))
    }

    // Now we have the correct last bar, add the price or trade.
    if (amount.isDefined) {
      series.addTrade(amount.get, price)
    } else {
      series.addPrice(price)
    }
    if (addedNewBar)
      ctx.send(CandleAdd(key, barToCandle(series.getLastBar)))
    else ctx.send(CandleUpdate(key, barToCandle(series.getLastBar)))
  }

  private def _record(key: String, candle: Candle)
                     (implicit ctx: TradingSession): Unit = {
    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key ->
        new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build())
    }
    val series = allSeries(key)

    val alignedMillis = getGlobalIndex(candle.micros) * barSize.toMillis
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(alignedMillis), ZoneOffset.UTC)

    // Until the last bar exists and accepts the current time, create a new bar.
    while (series.getBarCount == 0 || !series.getLastBar.inPeriod(zdt)) {
      val lastBar: Option[Bar] = if (series.getBarCount == 0) None else Some(series.getLastBar)
      val startingTime = if (lastBar.isEmpty) zdt else lastBar.get.getEndTime
      // But, before we add the new bar, we make sure the last one isn't empty. If it empty,
      // copy over the close data from the one before it so that calculations aren't messed up.
      if (lastBar.isDefined && !hasNonZeroClosePrice(lastBar.get)) {
        // The second to last bar should always exist if an empty last bar exists. Furthermore,
        // it should never be empty.
        val secondToLastBar = series.getBar(series.getEndIndex - 1)
        lastBar.get.addPrice(secondToLastBar.getClosePrice)
        ctx.send(CandleUpdate(key, barToCandle(lastBar.get)))
      }
      // Ok, now we can add the new bar.
      series.addBar(barSize, startingTime.plus(barSize))
    }

    val curBar = series.getLastBar
    val curBarIsEmpty = !hasNonZeroClosePrice(curBar)
    val fn = series.function
    val newOpen = if (curBarIsEmpty) fn(candle.open) else curBar.getOpenPrice
    val newHigh = if (curBarIsEmpty) fn(candle.high) else curBar.getMaxPrice.max(fn(candle.high))
    val newLow = if (curBarIsEmpty) fn(candle.low) else curBar.getMinPrice.min(fn(candle.low))
    val newClose = fn(candle.close)
    val newVolume = curBar.getVolume.plus(fn(candle.volume))
    val newBar = new BaseBar(curBar.getTimePeriod, curBar.getEndTime, newOpen, newHigh,
      newLow, newClose, newVolume, fn(0))
    series.addBar(newBar, true)

    // Update the report
    if (curBarIsEmpty)
      ctx.send(CandleAdd(key, barToCandle(newBar)))
    else ctx.send(CandleUpdate(key, barToCandle(newBar)))
  }

  def recordCandle(market: Market, candle: Candle)
                  (implicit ctx: TradingSession): Unit =
    _record(priceKey(market.exchange, market.symbol), candle)

  def recordCandle(exchange: String, product: String, candle: Candle)
                  (implicit ctx: TradingSession): Unit =
    _record(priceKey(exchange, product), candle)

  def prices(exchange: String, product: String): TimeSeries = _series(priceKey(exchange, product))

  def prices(market: Market): TimeSeries = _series(priceKey(market.exchange, market.symbol))

  def series(key: String): TimeSeries = _series(indicatorKey(key))

  private def _series(key: String): TimeSeries = {
    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key ->
        new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build())
    }
    allSeries(key)
  }

  def priceKey(exchange: String, product: String): String = s"price.$exchange.$product"

  def indicatorKey(name: String): String = s"indicator.$name"
}
