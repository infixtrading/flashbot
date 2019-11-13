package flashbot.strategies

import flashbot.core.{EngineLoader, MarketData, Strategy}
import flashbot.models.{Market, Portfolio}
import io.circe.generic.JsonCodec
import io.circe.parser._

import scala.concurrent.Future

@JsonCodec
case class FuturesArbParams()

class BTCFuturesArb extends Strategy[FuturesArbParams] {

  override def title = "BitMEX/Binance Bitcoin Futures Arbitrage"
  override def decodeParams(paramsStr: String) = decode[FuturesArbParams](paramsStr).toTry

  lazy val xtb = Market("bitmex/xbtusd")
  lazy val btc = Market("binance/btcusdt")

  override def initialize(portfolio: Portfolio, loader: EngineLoader) = {
    Future.successful(Seq())
  }

  override def onData(data: MarketData[_]): Unit = ???
}
