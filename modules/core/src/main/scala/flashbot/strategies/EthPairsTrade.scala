package flashbot.strategies

import flashbot.core.DataType.{CandlesType, TradesType}
import flashbot.core._
import flashbot.core.AssetKey.implicits._
import flashbot.models.{Candle, DataPath, Market, Portfolio}
import FixedSize._
import EthPairsTrade._
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.github.andyglow.jsonschema.AsCirce._
import flashbot.core.Instrument.CurrencyPair
import flashbot.core.MarketData.BaseMarketData
import flashbot.models.{DataOverride, DataSelection}
import flashbot.stats.Cointegration
import flashbot.util.NumberUtils
import io.circe.generic.JsonCodec
import io.circe.parser._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

@JsonCodec
case class PairsTradeParams(eth_market: String,
                            hedge_market: String,
                            mode: String,
                            threshold: Double)

class EthPairsTrade extends Strategy[PairsTradeParams] with TimeSeriesMixin {

  override def title = "ETH Pairs Trade"

  // coinbase.usd=4000,coinbase.eth=0,coinbase.etc=0,bitmex.ethusd=-10000x2

  override def decodeParams(paramsStr: String) = decode[PairsTradeParams](paramsStr).toTry

  lazy val ethMarket = Market(params.eth_market)
  lazy val hedgeMarket = Market(params.hedge_market)

  lazy val btcPath: DataPath[Candle] =
    DataPath("coinbase", "btc_usd", CandlesType(sessionBarSize))

  // Kalman filter
  val coint = new Cointegration(1e-10, 1e-7)

  override def initialize(portfolio: Portfolio, loader: EngineLoader) = {
    val marketsDataType = params.mode match {
      case AnalyzeMode => CandlesType(sessionBarSize)
      case TradeMode => TradesType
    }
    Future.successful(Seq(
      ethMarket.path(marketsDataType),
      hedgeMarket.path(marketsDataType),
      btcPath
    ))
  }

  private lazy val threshold = params.threshold

  var count = 0

  override def onData(data: MarketData[_]): Unit = data.data match {
    case candle: Candle if data.path == btcPath =>
      // Ignore

    case candle: Candle =>
      if (data.path.market == ethMarket)
        recordTimeSeries("eth_usd", data.micros, getPrice(ethMarket))
      else if (data.path.market == hedgeMarket)
        recordTimeSeries("hedge_usd", data.micros, getPrice(hedgeMarket))

      recordTimeSeries("hedge", data.micros, ctx.getPortfolio.getBalance(hedgeMarket.baseAccount))
      recordTimeSeries("eth", data.micros, ctx.getPortfolio.getBalance(ethMarket.baseAccount))
      recordTimeSeries("usd", data.micros, ctx.getPortfolio.getBalance(ethMarket.quoteAccount))

      // If the price indices are the same, update the kalman filter
      if (lastBarTime(ethMarket).isDefined &&
          lastBarTime(ethMarket) == lastBarTime(hedgeMarket)) {
        if (count > 4) {
          val (alpha, beta) = (coint.getAlpha, coint.getBeta)
          recordTimeSeries("alpha", data.micros, alpha)
          recordTimeSeries("beta", data.micros, beta)

          val estimate = alpha + beta * getPrice(ethMarket)
          recordTimeSeries("residual", data.micros, getPrice(hedgeMarket) - estimate)

          updatePositions(data.micros, estimate, beta)
        }

        count = count + 1
        coint.step(getPrice(ethMarket), getPrice(hedgeMarket))

        if (count > 4) {
          recordTimeSeries("error", data.micros, coint.getError)
          recordTimeSeries("variance", data.micros, coint.getVariance)
        }
      }

    case trade: Trade =>
      recordTrade(data.path.market, data.micros, trade.price, Some(trade.size))
  }

  sealed trait SpreadPosition
  case object Long extends SpreadPosition
  case object Short extends SpreadPosition
  case object Neutral extends SpreadPosition

  var spreadPosition: SpreadPosition = Neutral

  def updatePositions(micros: Long, hedgePrediction: Double, beta: Double): Unit = {
    val spread = getPrice(hedgeMarket) - hedgePrediction

    // How much is every ETHUSD contract worth in ETH currently?
    val contractValInEth = ctx
      .instruments("bitmex/ethusd")
      .value(getPrice(ethMarket))
      .as('eth)
      .amount

    // What is the ETH size of our short ETH position?
    val shortEthPos = contractValInEth * ctx.getPortfolio.getPosition("bitmex/ethusd").size

    // What is our current ETH balance on the main exchange?
    val ethBalance: Double = ctx.getPortfolio.getBalance(ethMarket.baseAccount)

    // Compute our total position using the values above.
    var totalEthPos = shortEthPos +
      ctx.getPortfolio.getBalanceSize("bitmex/xbt").as('eth).amount + ethBalance

    recordTimeSeries("short_eth_pos", micros, shortEthPos)
    recordTimeSeries("total_eth_pos", micros, totalEthPos)

    // Balance the neutral position. Order/sell enough ETH so that it cancels out the
    // short position.
    val roundedTotalEthPos = NumberUtils.floor(totalEthPos, 2)
    if (spreadPosition == Neutral && roundedTotalEthPos != 0) {
      ctx.submit(new MarketOrder(ethMarket, -roundedTotalEthPos))
      totalEthPos = totalEthPos - roundedTotalEthPos
    }

    // Close position
    spreadPosition match {
      case Long if spread > -threshold * 0.1 =>
        // We were long the spread, so we bought the hedge security and sold eth.
        // Now we want to exit by selling the security and buying back the eth.
        spreadPosition = Neutral
        ctx.submit(new MarketOrder(hedgeMarket,
          -ctx.getPortfolio.getBalance(hedgeMarket.securityAccount)))

        // Total eth position should be negative right now. Negate it again to make
        // a positive order size.
        ctx.submit(new MarketOrder(ethMarket, -totalEthPos))

      case _ =>
    }

    // Open position
    spreadPosition match {
      case Neutral if spread < -threshold =>
        // Long the spread if it's under the threshold.
        // This means that ETH is relatively overvalued.
        // Sell all that we have.
        spreadPosition = Long
        ctx.submit(new MarketOrder(ethMarket, -ethBalance))

        // At the same time, buy the hedge security.
        // Amount of ETH sold * (1 + beta)
        val hedgeAmount = ethBalance.of('eth).as(hedgeMarket.securityAccount).amount * (1.0 + beta)
        ctx.submit(new MarketOrder(hedgeMarket, hedgeAmount))

      case _ =>
    }
  }

  override def resolveMarketData[T](selection: DataSelection[T],
                                    dataServer: ActorRef,
                                    dataOverrides: Seq[DataOverride[Any]])
                                   (implicit mat: Materializer, ec: ExecutionContext)
      : Future[Source[MarketData[T], NotUsed]] =
    selection.path.datatype match {
      case CandlesType(d) if d > 1.minute =>
        val newPath = selection.path.withType(CandlesType(1 minute))
        super.resolveMarketData(selection.copy(path = newPath), dataServer, dataOverrides)
          .map(_.map(_.data)
            .via(PriceTap.aggregateCandles(d))
            .zipWithIndex
            .map { case (candle, i) =>
                BaseMarketData(candle.asInstanceOf[T], selection.path, candle.micros, 1, i)
            }
          )
      case _ =>
        super.resolveMarketData(selection, dataServer, dataOverrides)
    }

  override def info(loader: EngineLoader): Future[StrategyInfo] = {
    implicit val ec: ExecutionContext = loader.ec
    for {
      markets <- loader.markets
      ethMarkets = markets.filter(m => CurrencyPair.parse(m.symbol)
        .contains(CurrencyPair("eth", "usd"))).map(_.toString)
      hedgeMarkets = markets.filter(m => CurrencyPair.parse(m.symbol)
        .exists(_.quote == "usd")).map(_.toString) -- ethMarkets
      initial <- super.info(loader)
    } yield initial
      .withSchema(json.Json.schema[PairsTradeParams].asCirce().noSpaces)

      .withParamOptionsOpt("eth_market", ethMarkets.toSeq, ethMarkets.headOption)
      .withParamOptionsOpt("hedge_market", hedgeMarkets.toSeq, Some("coinbase.etc_usd"))
      .withParamOptionsOpt("mode", Seq(AnalyzeMode, TradeMode), Some(AnalyzeMode))
      .withParamDefault("threshold", 0.02)

      .updateLayout(_
        .addPanel("Prices")
        .addTimeSeries("eth_usd", "Prices")
        .addTimeSeries("hedge_usd", "Prices", _.setAxis(2))

        .addPanel("Balances", "Portfolio")
        .addTimeSeries("usd", "Balances")
        .addTimeSeries("eth", "Balances", _.setAxis(2))
        .addTimeSeries("hedge", "Balances", _.setAxis(2))
        .addTimeSeries("xbt", "Balances", _.setAxis(2))
        .addTimeSeries("short_eth_pos", "Balances", _.setAxis(2))
        .addTimeSeries("total_eth_pos", "Balances", _.setAxis(2))

        .addPanel("Kalman Filter", "Analysis")
        .addTimeSeries("alpha", "Kalman Filter")
        .addTimeSeries("beta", "Kalman Filter", _.setAxis(2))

        .addPanel("Residual", "Analysis")
        .addTimeSeries("residual", "Residual")

        .addPanel("Validation", "Analysis")
        .addTimeSeries("error", "Validation")
        .addTimeSeries("variance", "Validation", _.setAxis(2))
      )
  }
}

object EthPairsTrade {
  val AnalyzeMode = "analyze"
  val TradeMode = "trade"
}
