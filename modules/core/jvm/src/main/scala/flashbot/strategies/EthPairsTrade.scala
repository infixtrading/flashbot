package flashbot.strategies

import flashbot.core.DataType.{CandlesType, TradesType}
import flashbot.core._
import flashbot.models.core.{Candle, DataPath, FixedSize, Market, Portfolio}
import FixedSize._
import EthPairsTrade._
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.github.andyglow.jsonschema.AsCirce._
import flashbot.core.Instrument.CurrencyPair
import flashbot.core.MarketData.BaseMarketData
import flashbot.models.api.{DataOverride, DataSelection}
import flashbot.stats.{Cointegration, KalmanFilter}
import io.circe.generic.JsonCodec
import io.circe.parser._
import org.la4j.Matrix

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.math.BigDecimal.RoundingMode

@JsonCodec
case class PairsTradeParams(eth_market: String,
                            hedge_market: String,
                            mode: String,
                            threshold: Double)

class EthPairsTrade extends Strategy[PairsTradeParams] with TimeSeriesMixin {

  import FixedSize.numericDouble._

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

  var count = 0

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = data.data match {
    case candle: Candle if data.path == btcPath =>
      // Ignore

    case candle: Candle =>
      if (data.path.market == ethMarket)
        recordTimeSeries("eth_usd", data.micros, getPrice(ethMarket))
      else if (data.path.market == hedgeMarket)
        recordTimeSeries("hedge_usd", data.micros, getPrice(hedgeMarket))

      recordTimeSeries("hedge", data.micros, ctx.getPortfolio.balance(hedgeMarket.baseAccount).qty)
      recordTimeSeries("eth", data.micros, ctx.getPortfolio.balance(ethMarket.baseAccount).qty)
      recordTimeSeries("usd", data.micros, ctx.getPortfolio.balance(ethMarket.quoteAccount).qty)

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

  def updatePositions(micros: Long, hedgePrediction: Double, beta: Double)
                     (implicit ctx: TradingSession)= {
    val spread = getPrice(hedgeMarket) - hedgePrediction

    val contractVal = ctx.getInstruments("bitmex/ethusd").value(getPrice(ethMarket)).as("eth")
    val shortEthPos = contractVal * ctx.getPortfolio.positions("bitmex/ethusd").size.toDouble.of("eth")
    val ethBalance = ctx.getPortfolio.balance(ethMarket.baseAccount).size
    var totalEthPos = shortEthPos + ctx.getPortfolio.balance("bitmex/xbt").size.as("eth") + ethBalance

    recordTimeSeries("short_eth_pos", micros, shortEthPos.amount)
    recordTimeSeries("total_eth_pos", micros, totalEthPos.amount)

    // Balance the neutral position. Order/sell enough ETH so that it cancels out the
    // short position.
    val roundedTotalEthPos = BigDecimal(totalEthPos.amount)
      .setScale(2, RoundingMode.FLOOR).rounded.doubleValue()
    if (spreadPosition == Neutral && roundedTotalEthPos != 0) {
      marketOrder(ethMarket, -roundedTotalEthPos.of("eth"))
      totalEthPos = totalEthPos - roundedTotalEthPos.of("eth")
    }

    // Close position
    spreadPosition match {
      case Long if spread > -params.threshold * .1 =>
        // We were long the spread, so we bought the hedge security and sold eth.
        // Now we want to exit by selling the security and buying back the eth.
        spreadPosition = Neutral
        marketOrder(hedgeMarket,
          -ctx.getPortfolio.balance(hedgeMarket.securityAccount).size)

        // Total eth position should be negative right now. Negate it again to make
        // a positive order size.
        marketOrder(ethMarket, -totalEthPos)

      case _ =>
    }

    // Open position
    spreadPosition match {
      case Neutral if spread < -params.threshold =>
        // Long the spread if it's under the threshold.
        // This means that ETH is relatively overvalued.
        // Sell all that we have.
        spreadPosition = Long
        marketOrder(ethMarket, -ethBalance)

        // At the same time, buy the hedge security.
        // Amount of ETH sold * (1 + beta)
        marketOrder(hedgeMarket, ethBalance.as(hedgeMarket.securityAccount) * (1 + beta).of("etc"))
//        marketOrder(hedgeMarket, ethBalance.as(hedgeMarket.securityAccount))

      case _ =>
    }
  }

  override def resolveMarketData[T](selection: DataSelection[T],
                                    dataServer: ActorRef,
                                    dataOverrides: Seq[DataOverride[Any]])
                                   (implicit mat: Materializer, ec: ExecutionContext) = {
    selection.path.datatype match {
      case CandlesType(d) if d > 1.minute =>
        val newPath = selection.path.withType(CandlesType(1 minute))
        super.resolveMarketData(selection.copy(path = newPath), dataServer, dataOverrides)
          .map(_.map(_.data)
            .via(TimeSeriesTap.aggregateCandles(d))
            .zipWithIndex
            .map { case (candle, i) =>
                BaseMarketData(candle.asInstanceOf[T], selection.path, candle.micros, 1, i)
            }
          )
      case _ =>
        super.resolveMarketData(selection, dataServer, dataOverrides)
    }
  }

  override def info(loader: EngineLoader) = {
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
