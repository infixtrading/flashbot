package strategies
import akka.stream.Materializer
import com.infixtrading.flashbot.core.DataSource.StreamSelection
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.core.{DataSource, MarketData, TimeSeriesTap}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.Portfolio
import io.circe.generic.semiauto._

import scala.concurrent.Future
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

  override def resolveMarketData(streamSelection: StreamSelection)
                                (implicit mat: Materializer) = {
    Future.successful(Some(TimeSeriesTap
      .prices(1 day)
      .via(TimeSeriesTap.aggregateCandles(1 day))
      .throttle(1, 200 millis)
      .map(candle => BaseMarketData(candle, streamSelection.path, candle.micros, 1))))
  }
}
