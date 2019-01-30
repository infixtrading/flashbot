package flashbot.strategies

import flashbot.core.{SessionLoader, Strategy, _}
import flashbot.models.core._
import io.circe.generic.JsonCodec
import io.circe.parser._
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.volume.VWAPIndicator
import MarketMaker._

import scala.concurrent.Future

// This is the code for the tutorial at:
// https://github.com/infixtrading/flashbot/wiki/Custom-Strategy:-Market-Making

/**
  * The available parameters to customize the MarketMaker strategy.
  *
  * @param market the market to run on. E.g. "coinbase/btc_usd", or "bitmex/xbtusd".
  * @param datatype either "book", "trades", or "candles_1m".
  * @param fairPriceIndicator the name of an indicator to layer quotes around.
  * @param layersCount how many separate quotes on each side.
  * @param layerSpacing the distance in quote currency between each quote.
  * @param quoteSize the size of each limit order.
  */
@JsonCodec
case class MarketMakerParams(market: MarketParam,
                             datatype: DataTypeParam,
                             fairPriceIndicator: FairValueParam,
                             layersCount: Int,
                             layerSpacing: Double,
                             quoteSize: Double)

// Custom parameter type
class FairValueParam(val value: String) extends AnyVal
    with SchemaParam[FairValueParam, String] {
  override def schema(implicit loader: SessionLoader) =
    json.Schema.enum(Set(VWAP, SMA_7, SMA_14))
}

/**
  * An example market making strategy. It layers a number of asks and bids on each
  * side of the given fair-price indicator at configurable intervals.
  */
class MarketMaker extends Strategy[MarketMakerParams] with TimeSeriesMixin {

  override def title = "Market Maker"

  // Declare the indicators which may be selected for the "fair price" value. Only one of
  // these will actually be used at strategy runtime.
  val closePrice = new ClosePriceIndicator(prices(params.market))
  val fairPriceIndicators = Map(
    VWAP -> new VWAPIndicator(prices(params.market), 7),
    SMA_7 -> new SMAIndicator(closePrice, 7),
    SMA_14 -> new SMAIndicator(closePrice, 14)
  )

  override def decodeParams(paramsStr: String) = decode[MarketMakerParams](paramsStr).toTry

  /**
    * On initialization, we use the `market` and `datatype` parameters build a sequence of
    * DataPaths to subscribe to. If the datatype is "candles_1m", then that is the only
    * datatype that we'll use, as candles can be used by the simulator to fill orders.
    * Similarly with trades. However, if the `datatype` is
    *
    * @param portfolio the initial portfolio that this strategy starts with.
    * @param loader object that provides asynchronous access to various system information.
    * @return the list of DataPaths which we're subscribing to.
    */
  override def initialize(portfolio: Portfolio, loader: SessionLoader) = {
    Future.successful(Seq(
      DataPath(params.market.exchange, params.market.symbol,
        DataType(params.datatype.value))))
  }

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
      recordTrade(params.market, md.micros, trade.price, Some(trade.size))

    /**
      * Same as with trades, record the candle OHLC to the report and update the
      * price time series with `recordCandle`.
      */
    case candle: Candle =>
      recordCandle(params.market, candle)

    /**
      * Calculate and submit the intended quotes when there is new OrderBook data.
      */
    case book: OrderBook =>
      // Infer the tick size if we haven't already.
      tickSize = tickSize.orElse(Some(book.tickSize))

      // Calculate the fair price which we will quote around.
      val indicator = fairPriceIndicators(params.fairPriceIndicator)
      val fairPrice = indicator.getValue(indicator.getTimeSeries.getEndIndex).doubleValue()

      // Record the computed fair price value to a time series so that it's available
      // on dashboards.
      recordIndicator("fair_price", md.micros, fairPrice)

      // Calculate the best ask price in the book. We will not bid above it.
      val bestAskPrice = book.asks.index.head._1

      // Calculate the best bid price in the book. We will not ask below it.
      val bestBidPrice = book.bids.index.head._1

      // Declare at most `layersCount` number of quotes on each side.
      val instrument = ctx.instruments(params.market)
      for (i <- 1 to params.layersCount) {
        // Calculate the target ask price for the `i`th level.
        val targetAskPrice: Double = fairPrice + (i * params.layerSpacing)

        // Declare the ask order if the target ask price is above the best bid.
        limitOrder(
          market = params.market,
          size = (params.quoteSize, instrument.quote),
          price = targetAskPrice,
          key = s"ask_$i",
          postOnly = true
        )

        // Calculate the target bid price for the `i`th level.
        val targetBidPrice: Double = fairPrice - (i * params.layerSpacing)

        // Declare the limit order if the target bid price is below the best ask.
        if (targetBidPrice < bestAskPrice) {
          limitOrder(params.market, (params.quoteSize, instrument.quote), targetBidPrice,
            s"bid_$i", postOnly = true)
        }
      }
  }
}

object MarketMaker {
  val VWAP = "vwap"
  val SMA_7 = "sma7"
  val SMA_14 = "sma14"
}
