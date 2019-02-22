package flashbot.strategies

import flashbot.core.{EngineLoader, Strategy, _}
import flashbot.models.core._
import flashbot.models.core.FixedSize._
import io.circe.generic.JsonCodec
import io.circe.parser._
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.volume.VWAPIndicator
import MarketMaker._
import flashbot.core.DataType.{OrderBookType, TradesType}
import io.circe.{Decoder, Encoder}
import com.github.andyglow.jsonschema.AsCirce._
import flashbot.core.Layout._

import scala.concurrent.Future
import scala.language.implicitConversions

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
case class MarketMakerParams(market: Market,
                             datatype: String,
                             fairPriceIndicator: String,
                             indicatorBarCount: Int,
                             layersCount: Int,
                             layerSpacing: Double,
                             quoteSize: Double)

/**
  * An example market making strategy. It layers a number of asks and bids on each
  * side of the given fair-price indicator at configurable intervals.
  */
class MarketMaker extends Strategy[MarketMakerParams] with TimeSeriesMixin {

  override def title = "Market Maker"

  // Declare the close price indicator and the fair price indicator.
  // These values are lazy because `params` isn't initialized at the time that the
  // constructor is called.
  lazy val closePrice = new ClosePriceIndicator(prices(params.market))
  lazy val fairPriceIndicator = params.fairPriceIndicator match {
    case VWAP => new VWAPIndicator(prices(params.market), params.indicatorBarCount)
    case SMA => new SMAIndicator(closePrice, params.indicatorBarCount)
  }

  override def decodeParams(paramsStr: String) = decode[MarketMakerParams](paramsStr).toTry

  override def info(loader: EngineLoader) =
    Future.successful(defaultInfo
      .withSchema(json.Json.schema[MarketMakerParams].asCirce().noSpaces)
    )

  /**
    * On initialization, we use the `market` and `datatype` parameters build a sequence of
    * DataPaths to subscribe to. If the datatype is "candles_1m", then that is the only
    * datatype that we'll use, as candles can be used by the simulator to fill orders.
    * Same with trades. However, if the `datatype` is "book", then we'll also subscribe
    * to the "trades" stream, so that, during backtesting and paper trading, the simulator
    * can detect when our quotes would have been filled.
    *
    * @param portfolio the initial portfolio that this strategy starts with.
    * @param loader object that provides asynchronous access to various system information.
    * @return the list of DataPaths which we're subscribing to.
    */
  override def initialize(portfolio: Portfolio, loader: EngineLoader) = {
    val path = DataPath(params.market.exchange, params.market.symbol, DataType(params.datatype))
    val extraPaths =
      if (path.datatype == OrderBookType) Seq(path.withType(TradesType))
      else Seq.empty
    Future.successful(extraPaths :+ path)
  }

  override def handleEvent(event: StrategyEvent)
                          (implicit ctx: TradingSession) = event match {
    case ExchangeErrorEvent(error) =>
      println(error)
    case _ =>
  }

  override def handleData(md: MarketData[_])(implicit ctx: TradingSession): Unit = {

    implicit val instruments = ctx.instruments
    implicit val prices = ctx.getPrices

    val equity = ctx.getPortfolio.equity()

    recordTimeSeries("equity", md.micros, equity.num)

    md.data match {

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
        * price time series with `recordCandle`. Also submit quotes, approximating
        * the current best ask and bid as the close price.
        */
      case candle: Candle =>
        recordCandle(params.market, candle)
        submitQuotes(md, candle.close, candle.close)

      /**
        * Calculate and submit the intended quotes when there is new OrderBook data.
        */
      case book: OrderBook =>
        // Calculate the best ask price in the book. We will not bid above it.
        val bestAskPrice = book.asks.index.head._1

        // Calculate the best bid price in the book. We will not ask below it.
        val bestBidPrice = book.bids.index.head._1

        submitQuotes(md, bestAskPrice, bestBidPrice)
    }
  }

  /**
    * Helper method that is used to place/update/cancel our quotes upon incoming market data.
    *
    * @param md the market data supplied to `handleData`
    * @param bestAsk the best ask price in the book. We will not bid above it.
    * @param bestBid the best bid price in the book. We will not ask below it.
    */
  private def submitQuotes(md: MarketData[_], bestAsk: Double, bestBid: Double)
                          (implicit ctx: TradingSession): Unit = {

    // Calculate the fair price which we will quote around.
    val fairPrice = fairPriceIndicator.getValue(fairPriceIndicator.getTimeSeries.getEndIndex).doubleValue()

    // Record the computed fair price value to a time series so that it's available
    // on dashboards.
    recordTimeSeries(s"fair_price_${params.fairPriceIndicator}", md.micros, fairPrice)

    // Declare at most `layersCount` number of quotes on each side.
    val instrument = ctx.instruments(params.market)
    for (i <- 1 to params.layersCount) {
      // Calculate the target ask price for the `i`th level.
      val targetAskPrice: Double = fairPrice + (i * params.layerSpacing)

      // Declare the ask orders.
      limitOrder(
        market = params.market,
        size = (-params.quoteSize, instrument.quote),
        price = targetAskPrice,
        key = s"ask_$i",
        postOnly = true
      )

      // Calculate the target bid price for the `i`th level.
      val targetBidPrice: Double = fairPrice - (i * params.layerSpacing)

      // Declare the bid orders.
      limitOrder(
        market = params.market,
        size = (params.quoteSize, instrument.quote),
        price = targetBidPrice,
        key = s"bid_$i",
        postOnly = true
      )
    }
  }
}

object MarketMaker {
  val VWAP = "vwap"
  val SMA = "sma"
}
