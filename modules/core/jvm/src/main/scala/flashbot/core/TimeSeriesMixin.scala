package flashbot.core

import java.time.{Instant, ZoneOffset, ZonedDateTime}

import flashbot.core.ReportEvent.{CandleAdd, CandleUpdate}
import flashbot.core.Num._
import flashbot.models.core._
import flashbot.server.ServerMetrics
import flashbot.util.time._
import org.ta4j.core.BaseTimeSeries.SeriesBuilder
import org.ta4j.core.indicators.AbstractIndicator
import org.ta4j.core.{Bar, BaseBar, BaseTimeSeries, TimeSeries}
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.num

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

trait TimeSeriesMixin extends DataHandler { self: Strategy[_] =>

  private def barSize: FiniteDuration = self.sessionBarSize

  // The number of bars in the duration
  private def barCount(duration: FiniteDuration): Int = (duration.toMicros / barSize.toMicros).toInt

  private var allSeries: Map[String, BaseTimeSeries] = Map.empty

  private var closePriceIndicators: Map[String, ClosePriceIndicator] = Map.empty

  def getPrice(market: Market): Num =
    valueOf(closePrices(market)).doubleValue().num

  private def getGlobalIndex(micros: Long): Long = micros / (barSize.toMillis * 1000)

  def barToCandle(bar: Bar): Candle = {
    val micros = bar.getBeginTime.toInstant.toEpochMilli * 1000
    Candle(micros,
      bar.getOpenPrice.getDelegate.doubleValue().num,
      bar.getMaxPrice.getDelegate.doubleValue().num,
      bar.getMinPrice.getDelegate.doubleValue().num,
      bar.getClosePrice.getDelegate.doubleValue().num,
      bar.getVolume.getDelegate.doubleValue().num)
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

  private def buildTimeSeries(name: String) = new BaseTimeSeries.SeriesBuilder()
    .withName(name)
    .withMaxBarCount(100)
    .withNumTypeOf(num.DoubleNum.valueOf(_))
    .build()

  private def _record(key: String, micros: Long,
                      updateLastBar: (TimeSeries, Boolean) => Unit)
                     (implicit ctx: TradingSession): Unit = {
    val timer = ServerMetrics.startTimer("ts_record", Map("strategy" -> self.title))

    if (!allSeries.isDefinedAt(key)) {
      allSeries += (key -> buildTimeSeries(key))
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
    val ret = if (addedBars > 0) {
      ctx.send((1 to addedBars).map(_ - 1).reverse.map(i =>
        CandleAdd(key, barToCandle(series.getBar(series.getEndIndex - i)))):_*)
    } else ctx.send(CandleUpdate(key, barToCandle(series.getLastBar)))

    timer.close()
    ret
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
      val newOpen = if (isNewBar) fn(candle.open.toDouble()) else curBar.getOpenPrice
      val newHigh = if (isNewBar) fn(candle.high.toDouble()) else curBar.getMaxPrice.max(fn(candle.high.toDouble()))
      val newLow = if (isNewBar) fn(candle.low.toDouble()) else curBar.getMinPrice.min(fn(candle.low.toDouble()))
      val newClose = fn(candle.close.toDouble())
      val newVolume = curBar.getVolume.plus(fn(candle.volume.toDouble()))
      val newBar = new BaseBar(curBar.getTimePeriod, curBar.getEndTime, newOpen,
        newHigh, newLow, newClose, newVolume, fn(0))
      ts.addBar(newBar, true)
    })

  def prices(exchange: String, product: String): TimeSeries = _series(priceKey(exchange, product))

  def prices(market: Market): TimeSeries = _series(priceKey(market.exchange, market.symbol))

  def timeSeries(key: String): TimeSeries = _series(key)

  private def _series(key: String): TimeSeries = {
    if (!allSeries.isDefinedAt(key)) {
      val ts = buildTimeSeries(key)
      allSeries += (key -> ts)
    }
    allSeries(key)
  }

  def priceKey(exchange: String, product: String): String = s"$exchange.$product"
  def priceKey(market: Market): String = priceKey(market.exchange, market.symbol)

  abstract override def aroundHandleData(md: MarketData[_])(implicit ctx: TradingSession) = {
    md.data match {
      case trade: Trade =>
        recordTrade((md.source, md.topic), md.micros, trade.price, Some(trade.size))
      case candle: Candle =>
        recordCandle((md.source, md.topic), candle)
      case priced: Priced =>
        recordTrade((md.source, md.topic), md.micros, priced.price.toDouble())
      case _ =>
    }

    super.aroundHandleData(md)

    val t1 = ServerMetrics.startTimer("ts_equity_calc", Map("strategy" -> self.title))
    val initialPortfolio = self.getInitialPortfolio()
    val equities: Option[(Num, Num)] =
      if (initialPortfolio.isDefined) {
        val equity = ctx.getPortfolio.getEquity()
        val buyAndHold = initialPortfolio.get.getEquity()
        Some((equity, buyAndHold))
      } else None
    t1.close()

    if (equities.isDefined) {
      recordTimeSeries("equity", md.micros, equities.get._1.toDouble())
      recordTimeSeries("buy_and_hold", md.micros, equities.get._2.toDouble())
    }
  }

  def valueOf[T](indicator: AbstractIndicator[T]): T =
    indicator.getValue(indicator.getTimeSeries.getEndIndex)

  def closePrices(market: Market): ClosePriceIndicator = {
    val key = priceKey(market)
    val indicator = closePriceIndicators.getOrElse(key,
      new ClosePriceIndicator(_series(key)))
    closePriceIndicators += (key -> indicator)
    indicator
  }

  def index(market: Market): Int = prices(market).getEndIndex
  def lastBarTime(market: Market): Option[ZonedDateTime] = {
    val ts = prices(market)
    (if (ts.isEmpty) None else Some(ts.getLastBar)).map(_.getBeginTime)
  }
}
