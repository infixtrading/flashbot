package com.infixtrading.flashbot.models.api
import java.time.Instant

import com.infixtrading.flashbot.core.FlashbotConfig.BotConfig
import com.infixtrading.flashbot.core.Transaction
import io.circe.Json

import scala.collection.SortedSet

case class ExchangeState(params: Json)

case class BotState(config: Option[BotConfig],
                    enabled: Boolean,
                    lastHeartbeatMicros: Long,
                    sessions: Seq[TradingSessionState]) {
  def pushSession(session: TradingSessionState) = copy(sessions = sessions :+ session)
  def updateLastSession(fn: TradingSessionState => TradingSessionState) =
    copy(sessions = sessions.updated(sessions.length - 1, fn(sessions.last)))
}

object BotState {
  def empty(micros: Long): BotState = BotState(None, enabled = false, micros, Seq())
}

sealed trait BotStatus
// Enabled state and session responding to ping
case object Running extends BotStatus
// Enabled state but session not responding to ping
case object Crashed extends BotStatus
// Disabled state
case object Disabled extends BotStatus

case class TradingEngineState(bots: Map[String, BotState] = Map.empty,
                              exchanges: Map[String, ExchangeState] = Map.empty,
                              transactions: Map[String, SortedSet[Transaction]] = Map.empty,
                              startedAtMicros: Option[Long] = None) {
  /**
    * A pure function that updates the state in response to an event that occurred in the
    * engine. No side effects or outside state please! You can log tho.
    */
  def update(event: TradingEngineEvent): TradingEngineState = event match {
    case EngineStarted(micros) =>
      copy(startedAtMicros = Some(micros))

    case BotConfigured(micros, id, config) =>
      val state = bots.getOrElse(id, BotState.empty(micros)).copy(config = Some(config))
      copy(bots = bots + (id -> state ))

    case BotEnabled(id) =>
      copy(bots = bots + (id -> bots(id).copy(enabled = true)))

    case BotDisabled(id) =>
      copy(bots = bots + (id -> bots(id).copy(enabled = false)))

    case BotHeartbeatEvent(id, micros) =>
      copy(bots = bots + (id -> bots(id).copy(lastHeartbeatMicros = micros)))

    case BotExpired(id) =>
      copy(bots = bots - id)

    /**
      * A bot session started.
      */
    case SessionStarted(id, Some(botId), strategyKey, strategyParams, mode,
        micros, portfolio, report) =>
      val session = TradingSessionState(id, strategyKey, strategyParams,
        mode, micros, portfolio, report)
      copy(bots = bots + (botId ->
        bots.getOrElse(botId, BotState.empty(micros)).pushSession(session) ))

    /**
      * A bot session updated.
      */
    case e: SessionUpdated => e match {
      case ReportUpdated(botId, delta) =>
        copy(bots = bots + (botId -> bots(botId).updateLastSession(_.updateReport(delta))))

      case BalancesUpdated(botId, account, balance) =>
        copy(bots = bots + (botId -> bots(botId).updateLastSession(session => session.copy(
          portfolio = session.portfolio.withAssetBalance(account, balance)
        ))))

      case PositionUpdated(botId, market, position) =>
        copy(bots = bots + (botId -> bots(botId).updateLastSession(session => session.copy(
          portfolio = session.portfolio.unsafeSetPosition(market, position)
        ))))

    }
  }

  def expireBots(now: Instant): TradingEngineState =
    copy(bots = bots.filter {
      case (id, bot) =>
        !bot.enabled || bot.config.flatMap(_.ttl).forall { ttl =>
          bot.lastHeartbeatMicros + ttl.toMicros >= now.toEpochMilli * 1000
        }
    })
}
