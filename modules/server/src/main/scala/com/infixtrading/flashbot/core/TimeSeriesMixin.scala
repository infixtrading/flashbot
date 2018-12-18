package com.infixtrading.flashbot.core

import java.time.{Duration, Instant, ZoneOffset, ZonedDateTime}

import com.infixtrading.flashbot.engine.{Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.Candle
import com.infixtrading.flashbot.report.ReportEvent.{CandleAdd, CandleUpdate}
import com.infixtrading.flashbot.util
import org.ta4j.core.num.Num
import org.ta4j.core.{Bar, BaseBar, BaseTimeSeries, TimeSeries}

trait TimeSeriesMixin { self: Strategy =>

  def timePeriod = self.sessionBarSize

  var allSeries: Map[String, TimeSeries] = Map.empty

  def getGlobalIndex(micros: Long): Long = micros / timePeriod.toMillis

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

  def record(exchange: String,
             product: String,
             micros: Long,
             price: Double,
             amount: Option[Double] = None)
            (implicit ctx: TradingSession): Unit = {
    val key = _key(exchange, product)
    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key ->
        new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build())
    }
    val series = allSeries(key)

    val alignedMillis = getGlobalIndex(micros) * timePeriod.toMillis
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
      series.addBar(timePeriod, startingTime.plus(timePeriod))
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

  def record(exchange: String,
             product: Instrument,
             micros: Long,
             price: Double,
             amount: Option[Double])
            (implicit ctx: TradingSession): Unit =
    record(exchange, product.toString, micros, price, amount)

  def record(exchange: String,
             product: String,
             candle: Candle)
            (implicit ctx: TradingSession): Unit = {
    val key = _key(exchange, product)
    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key ->
        new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build())
    }
    val series = allSeries(key)

    val alignedMillis = getGlobalIndex(candle.micros) * timePeriod.toMillis
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
      series.addBar(timePeriod, startingTime.plus(timePeriod))
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

  def record(exchange: String,
             product: Instrument,
             candle: Candle)
            (implicit ctx: TradingSession): Unit = record(exchange, product.toString, candle)

  def get(exchange: String, product: String): Option[TimeSeries] =
    allSeries.get(_key(exchange, product))

  def get(exchange: String, product: Instrument): Option[TimeSeries] = get(exchange, product.toString)

  def _key(exchange: String, product: String): String = s"$exchange.$product"
}
