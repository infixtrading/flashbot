package com.infixtrading.flashbot.strategies

import com.infixtrading.flashbot.core.{MarketData, Priced, TimeSeriesMixin, Trade}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.{DataPath, Portfolio}
import io.circe.Decoder
import io.circe.generic.JsonCodec
import io.circe.generic.semiauto._
import io.prometheus.client.{Counter, Summary}

import scala.concurrent.Future

class TimeSeriesStrategy extends Strategy with TimeSeriesMixin {

  type Params = TimeSeriesStrategy.Params

  override def paramsDecoder = implicitly[Decoder[TimeSeriesStrategy.Params]]

  override def title = "Time Series Strategy"

  override def initialize(portfolio: Portfolio, loader: SessionLoader) =
    Future.successful(Seq(params.path))

  override def handleData(marketData: MarketData[_])(implicit ctx: TradingSession) = marketData.data match {

    case trade: Trade =>
      TimeSeriesStrategy.dataCounter.inc()
      record(marketData.source, marketData.topic, marketData.micros, trade.price, Some(trade.size))

    case data: Priced =>
      TimeSeriesStrategy.dataCounter.inc()
      record(marketData.source, marketData.topic, marketData.micros, data.price)

    case x => // Ignore non-priced data
  }
}

object TimeSeriesStrategy {
  @JsonCodec case class Params(path: DataPath)

  lazy val dataCounter = Counter.build("time_series_strategy_data_count",
    "Counter of data items sent to the TimeSeriesStrategy").register()
}
