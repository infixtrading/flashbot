package strategies
import com.infixtrading.flashbot.core.{MarketData, Trade}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.core.State.ops._
import io.circe.generic.semiauto._

import scala.concurrent.Future

class TradeWriter extends Strategy {

  case class Params(trades: Seq[Trade])

  def paramsDecoder = deriveDecoder[Params]

  def title = "Trade Writer"

  def initialize(portfolio: Portfolio, loader: SessionLoader) =
    Future.successful(Seq("bitfinex/btc_usd/trades"))

  def handleData(data: MarketData[_])(implicit ctx: TradingSession) = data.data match {
    case trade: Trade =>
      "last_trade".set(trade)
  }
}
