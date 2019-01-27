package flashbot.strategies

import flashbot.core._
import flashbot.engine.{SessionLoader, Strategy, TradingSession}
import flashbot.core.MarketData
import flashbot.models.core.{DataPath, Portfolio}
import io.circe.Decoder
import io.circe.generic.JsonCodec
import io.circe.generic.semiauto._
import io.circe.syntax._

import scala.concurrent.Future

class TimeSeriesStrategy extends Strategy with TimeSeriesMixin {

  type Params = TimeSeriesStrategy.Params

  override def paramsDecoder = implicitly[Decoder[TimeSeriesStrategy.Params]]

  override def title = "Time Series Strategy"

  override def initialize(portfolio: Portfolio, loader: SessionLoader): Future[Seq[DataPath[_]]] =
    Future.successful(Seq(params.path))

  override def handleData(marketData: MarketData[_])(implicit ctx: TradingSession) = marketData.data match {

    case trade: Trade =>
      Metrics.inc("time_series_strategy_data_count")
      observePrice(marketData.source, marketData.topic, marketData.micros, trade.price, Some(trade.size))

    case data: Priced =>
      Metrics.inc("time_series_strategy_data_count")
      observePrice(marketData.source, marketData.topic, marketData.micros, data.price)

    case x => // Ignore non-priced data
  }
}

object TimeSeriesStrategy {
  @JsonCodec case class Params(path: String)
}
