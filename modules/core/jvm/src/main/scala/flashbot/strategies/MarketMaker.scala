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
import flashbot.core.DataType.{CandlesType, OrderBookType, TradesType}
import io.circe.{Decoder, Encoder}
import com.github.andyglow.jsonschema.AsCirce._
import flashbot.models.core.Order.Maker

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.{implicitConversions, postfixOps}

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
case class MarketMakerParams(market: String,
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
  import FixedSize.numericDouble._

  override def title = "Market Maker"

  // The fair price indicator using the built-in close price indicator.
  // These values are lazy because `params` isn't initialized at the time that the
  // constructor is called.
  lazy val market = Market(params.market)
  lazy val fairPriceIndicator = params.fairPriceIndicator match {
    case VWAP => new VWAPIndicator(prices(params.market), params.indicatorBarCount)
    case SMA => new SMAIndicator(closePrices(market), params.indicatorBarCount)
  }

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
    val path = DataPath(market.exchange, market.symbol, DataType(params.datatype))
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

  /**
    * Submit quotes when new data comes in. When using trades and candlestick data,
    * we use the last price to approximate the best bid and ask prices to quote
    * around. When running this strategy on order book data, we use the actual
    * best ask and best bid.
    */
  override def handleData(md: MarketData[_])(implicit ctx: TradingSession): Unit = {
    md.data match {
      // Incoming trade data
      case trade: Trade =>
        submitQuotes(md, getPrice(market), getPrice(market))

      // Incoming candlestick data
      case candle: Candle =>
        submitQuotes(md, candle.close, candle.close)

      // Incoming order book
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

    recordTimeSeries("position", md.micros,
      ctx.getPortfolio.balance(market.securityAccount).size.amount)
    recordTimeSeries("cash", md.micros,
      ctx.getPortfolio.balance(market.settlementAccount).size.amount)

    // Record the computed fair price value to a time series so that it's available
    // on dashboards.
    recordTimeSeries(s"fair_price_${params.fairPriceIndicator}", md.micros, fairPrice)

    // Keep track of a temp portfolio object that we will subtract margin from when
    // calculating order sizes for each level, so that we know when we don't have
    // enough margin for any more orders, in which case, that target will be reset
    // to 0.
    var tempPortfolio = ctx.getPortfolio

    // Declare at most `layersCount` number of quotes on each side.
    for (i <- 1 to params.layersCount) {

      // Calculate the target ask price for the `i`th level.
      val targetAskPrice: Double = fairPrice + (i * params.layerSpacing)

      // Submit the ask if there is enough funds/margin.
      tempPortfolio = tryQuote(tempPortfolio, Ask, targetAskPrice, bestBid, i)

      // Calculate the target bid price for the `i`th level.
      val targetBidPrice: Double = fairPrice - (i * params.layerSpacing)

      // Submit the ask if there is enough funds/margin.
      tempPortfolio = tryQuote(tempPortfolio, Bid, targetBidPrice, bestAsk, i)

      if (i < 4) {
        recordTimeSeries(s"bid_$i", md.micros, targetBidPrice)
        recordTimeSeries(s"ask_$i", md.micros, targetAskPrice)
      }
    }
  }

  def tryQuote(portfolio: Portfolio, side: QuoteSide, price: Double,
               bestQuote: Double, index: Int)
              (implicit ctx: TradingSession): Portfolio = {
    val instrument = ctx.getInstruments(params.market)

    // First get the order cost.
    val size = if (side == Ask) -params.quoteSize else params.quoteSize

    // We should submit the order if the price does not extend beyond the
    // existing best quote, and if we have enough funds/margin for the order.
    val shouldOrder = (side match {
      case Ask => price > bestQuote
      case Bid => price < bestQuote
    }) && {
      val cost = portfolio.orderCost(market, size, price, Maker)

      // Determine the account which the cost is in terms of.
      val account = Account(market.exchange, cost.security)

      // Now get the available balance of that account.
      val balance = portfolio.availableBalance(account)

      balance > cost
    }

    // If the balance is big enough for the order, submit the order target.
    // Otherwise, disable the target by setting the size to `0`.
    val id = limitOrder(
      market = market,
      size = (if (shouldOrder) size else 0, instrument.base),
      price = price,
      key = s"${side}_$index",
      postOnly = true
    )

    // Now, return the new temporary portfolio so that we can keep looping
    // over it.
    if (shouldOrder) portfolio.withOrder(Some(id), market, size, price)
    else portfolio
  }

  override def decodeParams(paramsStr: String) = decode[MarketMakerParams](paramsStr).toTry

  override def info(loader: EngineLoader) = for {
    markets <- loader.markets
    initial <- super.info(loader)
  } yield initial
    // Generate a JSON Schema automatically from the params class.
    .withSchema(json.Json.schema[MarketMakerParams].asCirce().noSpaces)

    // Add available options to the "market", "datatype", and "fairPriceIndicator" params.
    // This also sets the default for each parameter as the third argument.
    .withParamOptions("market", markets.toSeq, markets.head)
    .withParamOptions("datatype", Seq(TradesType, CandlesType(1 minute)), TradesType)
    .withParamOptions("fairPriceIndicator", fairPriceIndicators, SMA)

    // Set defaults for the rest of the fields.
    .withParamDefault("indicatorBarCount", 7)
    .withParamDefault("layersCount", 10)
    .withParamDefault("layerSpacing", 0.5)
    .withParamDefault("quoteSize", 0.1)

    // Update the layout
    .updateLayout(_
      .addPanel("Prices")
      .addTimeSeries("${market:json}", "Prices")
      .addTimeSeries("fair_price_${fairPriceIndicator}", "Prices")
      .addTimeSeries("bid_1", "Prices", _.setFill(false).setColor("rgba(255,255,255,.2)"))
      .addTimeSeries("bid_2", "Prices", _.setFill(false).setColor("rgba(255,255,255,.2)"))
      .addTimeSeries("bid_3", "Prices", _.setFill(false).setColor("rgba(255,255,255,.2)"))
      .addTimeSeries("ask_1", "Prices", _.setFill(false).setColor("rgba(255,255,255,.2)"))
      .addTimeSeries("ask_2", "Prices", _.setFill(false).setColor("rgba(255,255,255,.2)"))
      .addTimeSeries("ask_3", "Prices", _.setFill(false).setColor("rgba(255,255,255,.2)"))

      .addPanel("Balances", "Portfolio")
      .addTimeSeries("position", "Balances")
      .addTimeSeries("cash", "Balances")
    )

}

object MarketMaker {
  val VWAP = "vwap"
  val SMA = "sma"

  val fairPriceIndicators = Seq(VWAP, SMA)
}
