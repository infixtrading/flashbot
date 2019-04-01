package flashbot.models

import java.time.{Duration, Instant}

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.scaladsl.Source
import flashbot.core.MarketData
import io.circe.Json

import scala.concurrent.duration.FiniteDuration

case class DataOverride[+T](path: DataPath[T], source: Source[MarketData[_], NotUsed])

sealed trait TradingEngineQuery
case object Ping extends TradingEngineQuery
case class BacktestQuery(strategyName: String,
                         params: Json,
                         timeRange: TimeRange,
                         portfolio: String,
                         barSize: Option[FiniteDuration],
                         eventsOut: Option[ActorRef] = None,
                         dataOverrides: Seq[DataOverride[Any]] = Seq.empty) extends TradingEngineQuery

case class BotReportQuery(botId: String) extends TradingEngineQuery
case class BotReportsQuery() extends TradingEngineQuery
case class BotSessionsQuery(botId: String) extends TradingEngineQuery
case class BotStatusQuery(botId: String) extends TradingEngineQuery
case class StrategiesQuery() extends TradingEngineQuery
case class StrategyInfoQuery(name: String) extends TradingEngineQuery

case object SyncExchanges extends TradingEngineQuery
case class SyncExchange(name: String) extends TradingEngineQuery

case class GetTxHistory(from: Instant = Instant.EPOCH) extends TradingEngineQuery
case class GetExchangeTxHistory(exchange: String,
                                from: Instant = Instant.EPOCH) extends TradingEngineQuery

case class GetPortfolioHistory(from: Instant = Instant.EPOCH,
                               timeStep: Duration = Duration.ofDays(1)) extends TradingEngineQuery

case class SubscribeToReport(botId: String) extends TradingEngineQuery

case object MarketDataIndexQuery extends TradingEngineQuery

sealed trait TimeSeriesQuery extends TradingEngineQuery {
  def path: DataPath[_]
  def range: TimeRange
  def interval: FiniteDuration
}
case class PriceQuery(path: DataPath[_], range: TimeRange, interval: FiniteDuration) extends TimeSeriesQuery


sealed trait StreamRequest[T]

sealed trait TakeLimit {
  def limit: Int
}
case class TakeFirst(limit: Int) extends TakeLimit
case class TakeLast(limit: Int) extends TakeLimit

object TakeLimit {
  def default: TakeLimit = TakeFirst(Int.MaxValue)
}

/**
  * Request a data stream source from the cluster. Returns a [[flashbot.server.CompressedSourceRef]]
  * if the sender is remote and just a Source[ MarketData[_] ] if the sender is local.
  */
case class DataStreamReq[T](selection: DataSelection[T],
                            takeLimit: TakeLimit = TakeLimit.default)
  extends StreamRequest[T] with TradingEngineQuery

/**
  * Used to request data from a DataSourceActor.
  */
case class StreamLiveData[T](path: DataPath[T]) extends StreamRequest[T]
