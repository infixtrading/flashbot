package examples

import com.infixtrading.flashbot.core.{Indicator, MarketData, TimeSeriesMixin, Trade}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.{OrderBook, Portfolio}
import io.circe.generic.semiauto._
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.volume.VWAPIndicator

/**
  * An example market making strategy. It subscribes to to Coinbase order books and layers
  * a number of asks and bids on each side of a given fair price indicator at configurable
  * intervals.
  *
  * This is the source code for the tutorial at:
  * https://github.com/infixtrading/flashbot/wiki/Tutorial%3A-Market-Making-Bot
  */
class MarketMaker extends Strategy with TimeSeriesMixin {
  override def title = "Market Maker"

  /**
    * This strategy can by configured by:
    *
    * @param exchange the exchange to run this strategy on.
    * @param market the market to run on. E.g. "btc_usd", or "xbtusd".
    * @param fairPriceIndicator the name of an indicator to layer quotes around.
    * @param layersCount how many separate quotes on each side.
    * @param layerSpacing the distance in quote currency between each quote.
    */
  case class Params(exchange: String, market: String, fairPriceIndicator: String,
                    layersCount: Int, layerSpacing: Double, readjustInterval: String)

  lazy val closePrice = new ClosePriceIndicator(series(params.exchange, params.market).get)
  lazy val vwap = new VWAPIndicator(series(params.exchange, params.market).get, 7)
  lazy val sma7 = new SMAIndicator(closePrice, 7)
  lazy val sma14 = new SMAIndicator(closePrice, 14)

  def fairPriceValue(): Double = params.fairPriceIndicator match {
    case "vwap" => vwap.getValue(vwap.getTimeSeries.getEndIndex).doubleValue()
    case "sma7" => sma7.getValue(sma7.getTimeSeries.getEndIndex).doubleValue()
    case "sma14" => sma14.getValue(sma14.getTimeSeries.getEndIndex).doubleValue()
  }

  // Automatically derive the JSON decoder.
  override def paramsDecoder = deriveDecoder[Params]

  /**
    * On initialization, we use the `exchange` and `market` parameters build a single
    * DataPath to subscribe to.
    *
    * @param portfolio the initial portfolio that this strategy starts with.
    * @param loader object that provides asynchronous access to various system information.
    * @return the list of DataPaths which we're subscribing to.
    */
  override def initialize(portfolio: Portfolio, loader: SessionLoader) = ???

  var tickSize: Option[Double] = None

  override def handleData(md: MarketData[_])(implicit ctx: TradingSession) = md.data match {

    /**
      * Record the last trade price when a trade comes in. This has two effects:
      *
      *   1. Provides updated source data for the indicators which depend on the
      *      price time series.
      *
      *   2. Writes the time series to the trading session report, which makes it
      *      available for analysis and graphing.
      */
    case trade: Trade =>
      record(params.exchange, params.market, md.micros, trade.price, Some(trade.size))

    /**
      * Calculate our intended quotes when there is new OrderBook data.
      */
    case book: OrderBook =>
      // Infer the tick size if we haven't already.
      tickSize = tickSize.orElse(Some(book.tickSize))

      // Calculate the fair price which we will quote around.
      val fairPrice = fairPriceValue()

      // Record the computed fair price value to it's own time series so that it's
      // available on dashboards.
      record("fair_price", md.micros, fairPrice)

      // Declare at most `layersCount` number of quotes on each side.
      val instrument = ctx.instruments(params.market)
      for (i <- 1 to params.layersCount) {
        val targetAskPrice: Double = fairPrice + (i * params.layerSpacing)
        limitOrder(params.market, (params.layerSpacing, instrument.quote), ???)
      }
  }
}
