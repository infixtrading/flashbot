package strategies
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import flashbot.core.MarketData.BaseMarketData
import flashbot.core.{SessionLoader, _}
import flashbot.core.VarState.ops._
import flashbot.models.api.{DataOverride, DataSelection}
import flashbot.models.core.Portfolio
import io.circe.generic.semiauto._
import io.circe.parser._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import TradeWriter._
import io.circe.{Decoder, Encoder}

import scala.language.postfixOps

class TradeWriter extends Strategy[Params] {

  def decodeParams(paramsStr: String) = decode[Params](paramsStr).toTry

  def title = "Trade Writer"

  def initialize(portfolio: Portfolio, loader: SessionLoader) =
    Future.successful(Seq("bitfinex/btc_usd/trades"))

  def handleData(data: MarketData[_])(implicit ctx: TradingSession) = data.data match {
    case trade: Trade =>
      "last_trade".set(trade)
  }

  override def resolveMarketData[T](selection: DataSelection[T], dataServer: ActorRef,
                                    dataOverrides: Seq[DataOverride[Any]])
                                   (implicit mat: Materializer, ec: ExecutionContext) = {
    Future.successful(Source(params.trades.toList)
      .throttle(1, 200 millis)
      .zipWithIndex
      .map {
        case (trade, i) => BaseMarketData(trade.asInstanceOf[T], selection.path, trade.micros, 1, i)
      })
  }
}

object TradeWriter {
  case class Params(trades: Seq[Trade])
  object Params {
    implicit def de: Decoder[Params] = deriveDecoder[Params]
    implicit def en: Encoder[Params] = deriveEncoder[Params]
  }
}
