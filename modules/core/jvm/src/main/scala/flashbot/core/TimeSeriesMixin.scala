package flashbot.core

import java.time.{Instant, ZoneOffset, ZonedDateTime}

import flashbot.core.ReportEvent.{CandleAdd, CandleUpdate}
import flashbot.models.core.{Candle, FixedSize, Market}
import flashbot.util.time._
import org.ta4j.core.{Bar, BaseBar, BaseTimeSeries, TimeSeries}
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num.Num

import scala.concurrent.duration.FiniteDuration

trait TimeSeriesMixin extends DataHandler { self: Strategy[_] =>

  def barSize: FiniteDuration = self.sessionBarSize

  // The number of bars in the duration
  def barCount(duration: FiniteDuration): Int = (duration.toMicros / barSize.toMicros).toInt

  var allSeries: Map[String, TimeSeries] = Map.empty

  var closePrices: Map[String, ClosePriceIndicator] = Map.empty

  def getPrice(market: Market): FixedSize[Double] = {
    val key = priceKey(market.exchange, market.symbol)
    val indicator = closePrices.getOrElse(key, new ClosePriceIndicator(allSeries(key)))
    val price = indicator.getValue(indicator.getTimeSeries.getEndIndex).doubleValue()
    FixedSize(price, market.symbol)
  }

  private def getGlobalIndex(micros: Long): Long = micros / (barSize.toMillis * 1000)

  def barToCandle(bar: Bar): Candle = {
    val micros = bar.getBeginTime.toInstant.toEpochMilli * 1000
    Candle(micros,
      bar.getOpenPrice.getDelegate.doubleValue(),
      bar.getMaxPrice.getDelegate.doubleValue(),
      bar.getMinPrice.getDelegate.doubleValue(),
      bar.getClosePrice.getDelegate.doubleValue(),
      bar.getVolume.getDelegate.doubleValue())
  }

  def recordTimeSeries(key: String, micros: Long, value: Double)
                      (implicit ctx: TradingSession): Unit =
    _recordPoint(key, micros, value, None)

  def recordTrade(market: Market,
                  micros: Long,
                  price: Double,
                  amount: Option[Double] = None)
                 (implicit ctx: TradingSession): Unit = {
    _recordPoint(priceKey(market.exchange, market.symbol), micros, price, amount)
  }

  private def _record(key: String, micros: Long,
                      updateLastBar: (TimeSeries, Boolean) => Unit)
                     (implicit ctx: TradingSession): Unit = {
    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key ->
        new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build())
    }
    val series = allSeries(key)

    val alignedMillis = getGlobalIndex(micros) * barSize.toMillis
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(alignedMillis), ZoneOffset.UTC)

    // If the data is outdated, then ignore.
    if (series.getBarCount > 0 && series.getLastBar.getBeginTime.isAfter(zdt)) {
      return
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
        val secondToLastBar = series.getBar(series.getEndIndex - 1)
        lastBar.get.addPrice(secondToLastBar.getClosePrice)
      }

      // Ok, now we can add the new bar.
      series.addBar(barSize, startingTime.plus(barSize))
      addedBars = addedBars + 1
    }

    updateLastBar(series, addedBars > 0)

    // Update the report
    if (addedBars > 0) {
      ctx.send((1 to addedBars).map(_ - 1).reverse.map(i =>
        CandleAdd(key, barToCandle(series.getBar(series.getEndIndex - i)))):_*)
    } else ctx.send(CandleUpdate(key, barToCandle(series.getLastBar)))
  }

    private def _recordPoint(key: String, micros: Long, price: Double,
                             amount: Option[Double])
                            (implicit ctx: TradingSession): Unit = {
      _record(key, micros, (ts, _) => {
        if (amount.isDefined) {
          ts.addTrade(amount.get, price)
        } else {
          ts.addPrice(price)
        }
      })
    }

  def recordCandle(market: Market, candle: Candle)
                  (implicit ctx: TradingSession): Unit =
    _record(priceKey(market.exchange, market.symbol), candle.micros, (ts, isNewBar) => {
      val curBar = ts.getLastBar
      val fn = ts.function
      val newOpen = if (isNewBar) fn(candle.open) else curBar.getOpenPrice
      val newHigh = if (isNewBar) fn(candle.high) else curBar.getMaxPrice.max(fn(candle.high))
      val newLow = if (isNewBar) fn(candle.low) else curBar.getMinPrice.min(fn(candle.low))
      val newClose = fn(candle.close)
      val newVolume = curBar.getVolume.plus(fn(candle.volume))
      val newBar = new BaseBar(curBar.getTimePeriod, curBar.getEndTime, newOpen,
        newHigh, newLow, newClose, newVolume, fn(0))
      ts.addBar(newBar, true)
    })

  def prices(exchange: String, product: String): TimeSeries = _series(priceKey(exchange, product))

  def prices(market: Market): TimeSeries = _series(priceKey(market.exchange, market.symbol))

  def timeSeries(key: String): TimeSeries = _series(key)

  private def _series(key: String): TimeSeries = {
    if (!allSeries.isDefinedAt(key)) {
      val ts = new BaseTimeSeries.SeriesBuilder().withName(key).withMaxBarCount(1000).build()
      allSeries += (key -> ts)
    }
    allSeries(key)
  }

  def priceKey(exchange: String, product: String): String = s"$exchange.$product"

  abstract override def aroundHandleData(data: MarketData[_])(implicit ctx: TradingSession) = {
    implicit val prices = ctx.getPrices
    implicit val instruments = ctx.instruments
    recordTimeSeries("equity", data.micros, ctx.getPortfolio.equity().num)
    super.aroundHandleData(data)
  }
}
