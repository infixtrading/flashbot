package strategies
import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.core.{DataSource, MarketData, TimeSeriesTap}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.api.DataSelection
import com.infixtrading.flashbot.models.core.Portfolio
import io.circe.generic.semiauto._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class CandleScanner extends Strategy {

  case class Params()

  override def paramsDecoder = deriveDecoder[Params]

  override def title = "Candle Scanner"

  override def initialize(portfolio: Portfolio, loader: SessionLoader) = {
    Future.successful(Seq("bitfinex/btc_usd/candles_1d"))
  }

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = {
    println(data)
  }

  override def resolveMarketData(selection: DataSelection, dataServer: ActorRef)
                       (implicit mat: Materializer, ec: ExecutionContext)
      : Future[Source[MarketData[_], NotUsed]] = {
    Future.successful(TimeSeriesTap
      .prices(1 day)
      .via(TimeSeriesTap.aggregateCandles(1 day))
      .throttle(1, 200 millis)
      .zipWithIndex
      .map {
        case (candle, i) => BaseMarketData(candle, selection.path, candle.micros, 1, i.toLong)
      }
    )
  }
}
