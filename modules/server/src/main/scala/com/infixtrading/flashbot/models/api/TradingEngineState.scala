package com.infixtrading.flashbot.models.api
import TradingSessionState

case class TradingEngineState(bots: Map[String, Seq[TradingSessionState]],
                              startedAtMicros: Long = 0) {
  /**
    * A pure function that updates the state in response to an event that occurred in the
    * engine. No side effects or outside state please!
    */
  def update(event: TradingEngineEvent): TradingEngineState = event match {
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
