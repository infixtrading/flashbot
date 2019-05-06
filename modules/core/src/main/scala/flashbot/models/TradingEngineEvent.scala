package flashbot.models

import flashbot.core.FlashbotConfig.BotConfig
import flashbot.core.{Report, ReportEvent}
import io.circe.Json

sealed trait TradingEngineEvent

sealed trait SessionInitEvent extends TradingEngineEvent

case class SessionInitialized(id: String,
                              botId: Option[String],
                              strategyKey: String,
                              strategyParams: Json,
                              mode: TradingSessionMode,
                              micros: Long,
                              report: Report) extends SessionInitEvent

case class SessionInitializationError(cause: Exception,
                                      botId: Option[String],
                                      strategyKey: String,
                                      strategyParams: Json,
                                      mode: TradingSessionMode,
                                      portfolio: String,
                                      report: Report) extends Exception with SessionInitEvent {
  override def getCause: Throwable = cause

  override def getMessage: String = s"Session failed to start. Mode: $mode. Bot: $botId. Strategy: $strategyKey."
}

case class EngineStarted(micros: Long) extends TradingEngineEvent

//sealed trait SessionUpdatedEvent extends TradingEngineEvent {
//  def botId: String
//}
case class ReportUpdated(botId: String, event: ReportEvent) extends TradingEngineEvent

case class BotConfigured(micros: Long, id: String, config: BotConfig) extends TradingEngineEvent

case class BotEnabled(botId: String) extends TradingEngineEvent
case class BotDisabled(botId: String) extends TradingEngineEvent
case class BotHeartbeatEvent(botId: String, micros: Long) extends TradingEngineEvent

case class BotExpired(botId: String) extends TradingEngineEvent
