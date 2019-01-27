package flashbot.models.api

import flashbot.config.BotConfig
import flashbot.report.{Report, ReportDelta}
import flashbot.models.core._
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
                                      portfolio: Portfolio,
                                      report: Report) extends TradingEngineEvent

case class EngineStarted(micros: Long) extends TradingEngineEvent

sealed trait SessionUpdated extends TradingEngineEvent {
  def botId: String
}
case class ReportUpdated(botId: String,
                         delta: ReportDelta) extends SessionUpdated
case class BalancesUpdated(botId: String,
                           account: Account,
                           balance: Double) extends SessionUpdated
case class PositionUpdated(botId: String,
                           market: Market,
                           position: Position) extends SessionUpdated

case class BotConfigured(micros: Long, id: String, config: BotConfig) extends TradingEngineEvent

case class BotEnabled(botId: String) extends TradingEngineEvent
case class BotDisabled(botId: String) extends TradingEngineEvent
case class BotHeartbeatEvent(botId: String, micros: Long) extends TradingEngineEvent

case class BotExpired(botId: String) extends TradingEngineEvent
