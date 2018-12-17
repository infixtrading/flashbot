package com.infixtrading.flashbot.models.api
import com.infixtrading.flashbot.core.FlashbotConfig.{BotConfig, ExchangeConfig}
import io.circe.Json

case class ExchangeState(params: Json)

case class BotState(config: Option[BotConfig], sessions: Seq[TradingSessionState]) {
  def pushSession(session: TradingSessionState) = copy(sessions = sessions :+ session)
  def updateLastSession(fn: TradingSessionState => TradingSessionState) =
    copy(sessions = sessions.updated(sessions.length - 1, fn(sessions.last)))
}

object BotState {
  def empty: BotState = BotState(None, Seq())
}

case class TradingEngineState(bots: Map[String, BotState] = Map.empty,
                              exchanges: Map[String, ExchangeState] = Map.empty,
                              startedAtMicros: Option[Long] = None) {
  /**
    * A pure function that updates the state in response to an event that occurred in the
    * engine. No side effects or outside state please! You can log tho.
    */
  def update(event: TradingEngineEvent): TradingEngineState = event match {
    case EngineStarted(micros) =>
      copy(startedAtMicros = Some(micros))

    /**
      * A bot session started.
      */
    case SessionStarted(id, Some(botId), strategyKey, strategyParams, mode,
        micros, portfolio, report) =>
      val session = TradingSessionState(id, strategyKey, strategyParams,
        mode, micros, portfolio, report)
      copy(bots = bots + (botId -> bots.getOrElse(botId, BotState.empty).pushSession(session) ))

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
}
