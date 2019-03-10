package flashbot.strategies

import flashbot.core.DataType.{CandlesType, TradesType}
import flashbot.core._
import flashbot.core.AssetKey._
import flashbot.core.Num._
import flashbot.models.core.{Candle, DataPath, Market, Portfolio}
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

  lazy val threshold = params.threshold.num

  var count = 0

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = data.data match {
    case candle: Candle if data.path == btcPath =>
      // Ignore

    case candle: Candle =>
      if (data.path.market == ethMarket)
        recordTimeSeries("eth_usd", data.micros, getPrice(ethMarket).toDouble())
      else if (data.path.market == hedgeMarket)
        recordTimeSeries("hedge_usd", data.micros, getPrice(hedgeMarket).toDouble())

      recordTimeSeries("hedge", data.micros, ctx.getPortfolio.getBalance(hedgeMarket.baseAccount).toDouble())
      recordTimeSeries("eth", data.micros, ctx.getPortfolio.getBalance(ethMarket.baseAccount).toDouble())
      recordTimeSeries("usd", data.micros, ctx.getPortfolio.getBalance(ethMarket.quoteAccount).toDouble())

      // If the price indices are the same, update the kalman filter
      if (lastBarTime(ethMarket).isDefined &&
          lastBarTime(ethMarket) == lastBarTime(hedgeMarket)) {
        if (count > 4) {
          val (alpha, beta) = (coint.getAlpha, coint.getBeta)
          recordTimeSeries("alpha", data.micros, alpha)
          recordTimeSeries("beta", data.micros, beta)

          val estimate = alpha + beta * getPrice(ethMarket).toDouble()
          recordTimeSeries("residual", data.micros, getPrice(hedgeMarket).toDouble() - estimate)

          updatePositions(data.micros, estimate.num, beta.num)
        }

        count = count + 1
        coint.step(getPrice(ethMarket).toDouble(), getPrice(hedgeMarket).toDouble())

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

  def updatePositions(micros: Long, hedgePrediction: Num, beta: Num)
                     (implicit ctx: TradingSession)= {
    val spread = getPrice(hedgeMarket) - hedgePrediction

    // How much is every ETHUSD contract worth in ETH currently?
    val contractValInEth = ctx
      .getInstruments("bitmex/ethusd")
      .value(getPrice(ethMarket))
      .convert("xbt", "eth")

    // What is the ETH size of our short ETH position?
    val shortEthPos = contractValInEth * ctx.getPortfolio.positions("bitmex/ethusd").size

    // What is our current ETH balance on the main exchange?
    val ethBalance = ctx.getPortfolio.getBalance(ethMarket.baseAccount)

    // Compute our total position using the values above.
    var totalEthPos = shortEthPos +
      ctx.getPortfolio.getBalance("bitmex/xbt").convert("xbt", "eth") +
      ethBalance

    recordTimeSeries("short_eth_pos", micros, shortEthPos.toDouble())
    recordTimeSeries("total_eth_pos", micros, totalEthPos.toDouble())

    // Balance the neutral position. Order/sell enough ETH so that it cancels out the
    // short position.
    val roundedTotalEthPos = totalEthPos.toBigDecimial.setScale(2, RoundingMode.FLOOR).rounded.num
    if (spreadPosition == Neutral && roundedTotalEthPos != `0`) {
      marketOrder(ethMarket, (-roundedTotalEthPos).of("eth")).send()
      totalEthPos = totalEthPos - roundedTotalEthPos
    }

    // Close position
    spreadPosition match {
      case Long if spread > -threshold * `0.1` =>
        // We were long the spread, so we bought the hedge security and sold eth.
        // Now we want to exit by selling the security and buying back the eth.
        spreadPosition = Neutral
        marketOrder(hedgeMarket,
            (-ctx.getPortfolio.getBalance(hedgeMarket.securityAccount))
              .of(hedgeMarket.securityAccount))
          .send()

        // Total eth position should be negative right now. Negate it again to make
        // a positive order size.
        marketOrder(ethMarket, (-totalEthPos).of("eth")).send()

      case _ =>
    }

    // Open position
    spreadPosition match {
      case Neutral if spread < -threshold =>
        // Long the spread if it's under the threshold.
        // This means that ETH is relatively overvalued.
        // Sell all that we have.
        spreadPosition = Long
        marketOrder(ethMarket, (-ethBalance).of("eth"))

        // At the same time, buy the hedge security.
        // Amount of ETH sold * (1 + beta)
        val hedgeAmount = ethBalance.convert("eth", hedgeMarket.securityAccount) * (`1` + beta)
        marketOrder(hedgeMarket, hedgeAmount.of(hedgeMarket.securityAccount))

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
