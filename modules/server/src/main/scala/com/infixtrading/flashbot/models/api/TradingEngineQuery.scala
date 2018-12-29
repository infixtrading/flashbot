package com.infixtrading.flashbot.models.api

import java.time.{Duration, Instant}

import akka.actor.ActorRef
import com.infixtrading.flashbot.models.core.TimeRange

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
