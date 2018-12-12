package com.infixtrading.flashbot.core

import java.time.{Duration, Instant, ZoneOffset, ZonedDateTime}

import com.infixtrading.flashbot.models.core.Candle
import com.infixtrading.flashbot.util
import org.ta4j.core.{BaseTimeSeries, TimeSeries}

class TimeSeriesGroup(period: Duration) {

  def this(periodStr: String) =
    this(Duration.ofNanos(util.time.parseDuration(periodStr).toNanos))

  var allSeries: Map[String, TimeSeries] = Map.empty

  def record(exchange: String,
             product: String,
             micros: Long,
             price: Double,
             amount: Option[Double] = None): Unit = {
    val key = _key(exchange, product)
    val series =
      if (allSeries.isDefinedAt(key)) allSeries(key)
      else new BaseTimeSeries.SeriesBuilder().withName(key).build()

    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(micros / 1000), ZoneOffset.UTC)

    // Until the last bar exists and accepts the current time, create a new bar.
    while (series.getBarCount == 0 || !series.getLastBar.inPeriod(zdt)) {
      val startingTime = if (series.getBarCount == 0) zdt else series.getLastBar.getEndTime
      series.addBar(period, startingTime.plus(period))
    }

    // Now we have the correct last bar, add the price or trade
    if (amount.isDefined) {
      series.addTrade(amount.get, price)
    } else {
      series.addPrice(price)
    }

    allSeries = allSeries + (key -> series)
  }

  def record(exchange: String,
            product: Instrument,
            micros: Long,
            price: Double,
            amount: Option[Double]): Unit =
    record(exchange, product.toString, micros, price, amount)

  def record(exchange: String,
             product: String,
             candle: Candle): Unit = {
    val key = _key(exchange, product)
    val series =
      if (allSeries.isDefinedAt(key)) allSeries(key)
      else new BaseTimeSeries.SeriesBuilder().withName(key).build()

    val zdt = ZonedDateTime
      .ofInstant(Instant.ofEpochMilli(candle.micros / 1000), ZoneOffset.UTC)

    while (series.getBarCount > 0 && !series.getLastBar.inPeriod(zdt.minus(period))) {
      series.addBar(period, series.getLastBar.getEndTime.plus(period))
    }

    if (candle.volume.isDefined) {
      series.addBar(zdt.plus(period), candle.open, candle.high, candle.low, candle.close, candle.volume.get)
    } else {
      series.addBar(zdt.plus(period), candle.open, candle.high, candle.low, candle.close)
    }

    allSeries = allSeries + (key -> series)
  }

  def record(exchange: String,
             product: Instrument,
             candle: Candle): Unit = record(exchange, product.toString, candle)

  def get(exchange: String, product: String): Option[TimeSeries] =
    allSeries.get(_key(exchange, product))

  def get(exchange: String, product: Instrument): Option[TimeSeries] = get(exchange, product.toString)

  def _key(exchange: String, product: String): String = s"$exchange.$product"
}
