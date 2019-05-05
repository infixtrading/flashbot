package flashbot.strategies

import flashbot.core._
import flashbot.core.FixedSize._
import flashbot.models.{DataPath, Market, Portfolio}
import io.circe.generic.JsonCodec
import io.circe.parser._
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.trading.rules.{CrossedDownIndicatorRule, CrossedUpIndicatorRule, StopGainRule, StopLossRule}

import scala.concurrent.Future

@JsonCodec
case class DMACParams(market: String, smaShort: Int, smaLong: Int)

class DMACStrategy extends Strategy[DMACParams] with TimeSeriesMixin {
  override def decodeParams(paramsStr: String) = decode[DMACParams](paramsStr).toTry

  override def title = "Dual Moving Average Crossover"

  lazy val market = Market(params.market)
  lazy val close = new ClosePriceIndicator(prices(market))
  lazy val smaShort = new SMAIndicator(close, params.smaShort)
  lazy val smaLong = new SMAIndicator(close, params.smaLong)

  lazy val crossedUp = new CrossedUpIndicatorRule(smaShort, smaLong)
  lazy val crossedDown = new CrossedDownIndicatorRule(smaShort, smaLong)

  override def initialize(portfolio: Portfolio, loader: EngineLoader) =
    Future.successful(Seq(DataPath(market, "candles_1m")))

  var isLong = false
  var enteredAt: Double = -1d

  val stopLoss = .97d
  val takeProfit = 1.02d

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = {
    val portfolio = ctx.getPortfolio
    val balance: FixedSize = portfolio.getBalanceSize(market.settlementAccount)
    val holding = portfolio.getBalanceSize(market.securityAccount)
    val price = getPrice(market)

    val hasCrossedUp = crossedUp.isSatisfied(index(market))
    val hasCrossedDown = crossedDown.isSatisfied(index(market))

    // 3% stop loss
    val stopLossTriggered = isLong && price < enteredAt * stopLoss

    // 2% take profit
    val takeProfitTriggered = isLong && price > enteredAt * takeProfit

    if (hasCrossedUp && !isLong) {
      isLong = true
      enteredAt = price
      marketOrder(market, balance * portfolio.getLeverage(market))

    } else if (stopLossTriggered || takeProfitTriggered || (hasCrossedDown && isLong)) {
      isLong = false
      enteredAt = `-1`
      marketOrder(market, -holding)
    }
  }
}
