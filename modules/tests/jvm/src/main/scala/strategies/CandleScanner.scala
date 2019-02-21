package strategies
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import flashbot.core.MarketData.BaseMarketData
import flashbot.core.{EngineLoader, _}
import flashbot.models.api.{DataOverride, DataSelection}
import flashbot.models.core.{Candle, Portfolio}
import io.circe.generic.auto._
import io.circe.parser._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps


case class CandleScannerParams()

class CandleScanner extends Strategy[CandleScannerParams] {

  def decodeParams(paramsStr: String) = decode[CandleScannerParams](paramsStr).toTry

  override def title = "Candle Scanner"

  override def initialize(portfolio: Portfolio, loader: EngineLoader) = {
    Future.successful(Seq("bitfinex/btc_usd/candles_1d"))
  }

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = {
    println(data)
  }

  override def resolveMarketData[T](selection: DataSelection[T], dataServer: ActorRef,
                                    dataOverrides: Seq[DataOverride[Any]])
                                   (implicit mat: Materializer, ec: ExecutionContext)
      : Future[Source[MarketData[T], NotUsed]] = {
    Future.successful(TimeSeriesTap
      .prices(1 day)
      .via(TimeSeriesTap.aggregateCandles(1 day))
      .throttle(1, 200 millis)
      .zipWithIndex
      .map {
        case (candle, i) => BaseMarketData(candle.asInstanceOf[T], selection.path, candle.micros, 1, i.toLong)
      }
    )
  }
}
