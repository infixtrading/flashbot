package com.infixtrading.flashbot.models.api

import java.time.{Duration, Instant}

import akka.actor.ActorRef
import com.infixtrading.flashbot.models.core.{DataPath, TimeRange}

import scala.concurrent.duration.FiniteDuration

sealed trait TradingEngineQuery
case object Ping extends TradingEngineQuery
case class BacktestQuery(strategyName: String,
                         params: String,
                         timeRange: TimeRange,
                         portfolio: String,
                         barSize: Option[FiniteDuration],
                         eventsOut: Option[ActorRef] = None) extends TradingEngineQuery

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


sealed trait StreamRequest[T]

/**
  * Request a data stream source from the cluster. Returns a [[com.infixtrading.flashbot.engine.CompressedSourceRef]]
  * if the sender is remote and just a Source[ MarketData[_] ] if the sender is local.
  */
case class DataStreamReq[T](selection: DataSelection) extends StreamRequest[T] with TradingEngineQuery

/**
  * Used to request data from a DataSourceActor.
  */
case class StreamLiveData[T](path: DataPath) extends StreamRequest[T]
