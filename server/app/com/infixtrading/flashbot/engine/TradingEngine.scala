package com.infixtrading.flashbot.engine

import akka.actor.ActorLogging
import akka.persistence.PersistentActor
import com.infixtrading.flashbot.core.{BotConfig, Report}
import com.infixtrading.flashbot.core.Exchange.ExchangeConfig

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(strategyClassNames: Map[String, String],
                    exchangeConfigs: Map[String, ExchangeConfig],
                    defaultBots: Map[String, BotConfig])
  extends PersistentActor with ActorLogging {


  sealed trait Response
  case object Pong extends Response {
    override def toString: String = "pong"
  }
  case class ReportResponse(report: Report) extends Response
  case class BotResponse(id: String, reports: Seq[Report]) extends Response
  case class BotsResponse(bots: Seq[BotResponse]) extends Response
  case class BotSessionsResponse(id: String, sessions: Seq[TradingSessionState]) extends Response
  case class StrategyResponse(name: String) extends Response
  case class StrategiesResponse(strats: Seq[StrategyResponse]) extends Response
  case class StrategyInfoResponse(title: String, key: String,
                                  info: Option[StrategyInfo]) extends Response
}


object TradingEngine {


  case class EngineState(bots: Map[String, Seq[TradingSessionState]],
                         startedAtMicros: Long = 0) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects or outside state please!
      */
    def update(event: Event): EngineState = event match {
      case EngineStarted(micros, withBots) =>
        copy(
          startedAtMicros = micros,
          bots = withBots.map(botId => botId -> Seq.empty[TradingSessionState]).toMap ++ bots
        )

      case SessionStarted(id, Some(botId), strategyKey, strategyParams, mode,
      micros, portfolio, report) =>
        copy(bots = bots + (botId -> (
          bots.getOrElse[Seq[TradingSessionState]](botId, Seq.empty) :+
            TradingSessionState(id, strategyKey, strategyParams, mode, micros, portfolio, report))))

      case e: SessionUpdated => e match {
        case ReportUpdated(botId, delta) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.updateReport(delta))))

        case BalancesUpdated(botId, account, balance) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.copy(portfolio = bot.last.portfolio.withBalance(account, balance)))))

        case PositionUpdated(botId, market, position) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.copy(portfolio = bot.last.portfolio.unsafeSetPosition(market, position)))))

      }
    }
  }

}
