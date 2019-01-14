package strategies
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.core.{DataSource, MarketData, Trade}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.core.State.ops._
import com.infixtrading.flashbot.engine.DataServer.DataSelection
import io.circe.generic.semiauto._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

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

  override def resolveMarketData(selection: DataSelection, dataServer: ActorRef)
                                (implicit mat: Materializer, ec: ExecutionContext) = {
    Future.successful(Source(params.trades.toList)
      .throttle(1, 200 millis)
      .zipWithIndex
      .map {
        case (trade, i) => BaseMarketData(trade, selection.path, trade.micros, 1, i)
      })
  }
}
