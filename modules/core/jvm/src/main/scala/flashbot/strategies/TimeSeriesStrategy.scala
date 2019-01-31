package flashbot.strategies

import flashbot.core.{MarketData, Strategy, _}
import flashbot.models.core.{DataPath, Portfolio}
import io.circe.Decoder
import io.circe.generic.JsonCodec
import io.circe.generic.semiauto._
import io.circe.parser._
import TimeSeriesStrategy._
import flashbot.server.Metrics

import scala.concurrent.Future

class TimeSeriesStrategy extends Strategy[Params] with TimeSeriesMixin {

  override def title = "Time Series Strategy"

  override def decodeParams(paramsStr: String) = decode[Params](paramsStr).toTry

  override def initialize(portfolio: Portfolio, loader: SessionLoader): Future[Seq[DataPath[_]]] =
    Future.successful(Seq(params.path))

  override def handleData(marketData: MarketData[_])(implicit ctx: TradingSession) = marketData.data match {

    case trade: Trade =>
      Metrics.inc("time_series_strategy_data_count")
      recordTrade((marketData.source, marketData.topic), marketData.micros, trade.price, Some(trade.size))

    case data: Priced =>
      Metrics.inc("time_series_strategy_data_count")
      recordTrade((marketData.source, marketData.topic), marketData.micros, data.price)

    case x => // Ignore non-priced data
  }
}

object TimeSeriesStrategy {
  @JsonCodec case class Params(path: String)
}
