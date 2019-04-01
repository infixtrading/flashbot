package flashbot.models

import flashbot.core.FlashbotConfig.BotConfig
import flashbot.core.{Report, ReportDelta}
import io.circe.Json

sealed trait TradingEngineEvent

case class SessionStarted(id: String,
                          botId: Option[String],
                          strategyKey: String,
                          strategyParams: Json,
                          mode: TradingSessionMode,
                          micros: Long,
                          portfolio: Portfolio,
                          report: Report) extends TradingEngineEvent

case class SessionInitializationError(cause: Exception,
                                      botId: Option[String],
                                      strategyKey: String,
                                      strategyParams: Json,
                                      mode: TradingSessionMode,
                                      portfolio: String,
                                      report: Report) extends TradingEngineEvent

case class EngineStarted(micros: Long) extends TradingEngineEvent

sealed trait SessionUpdated extends TradingEngineEvent {
  def botId: String
}
case class ReportUpdated(botId: String,
                         delta: ReportDelta) extends SessionUpdated
case class PortfolioUpdated(botId: String,
                            delta: PortfolioDelta) extends SessionUpdated

case class BotConfigured(micros: Long, id: String, config: BotConfig) extends TradingEngineEvent

case class BotEnabled(botId: String) extends TradingEngineEvent
case class BotDisabled(botId: String) extends TradingEngineEvent
case class BotHeartbeatEvent(botId: String, micros: Long) extends TradingEngineEvent

case class BotExpired(botId: String) extends TradingEngineEvent
