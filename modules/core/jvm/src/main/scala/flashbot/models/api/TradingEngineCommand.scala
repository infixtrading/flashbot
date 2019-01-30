package flashbot.models.api

import flashbot.config.BotConfig
import flashbot.core.ReportEvent
import flashbot.models.core.Portfolio

import scala.concurrent.duration.Duration

sealed trait TradingEngineCommand

case class ConfigureBot(id: String, config: BotConfig) extends TradingEngineCommand

case class EnableBot(id: String) extends TradingEngineCommand
case class DisableBot(id: String) extends TradingEngineCommand
case class BotHeartbeat(id: String) extends TradingEngineCommand

case class ProcessBotReportEvent(botId: String, event: ReportEvent) extends TradingEngineCommand

case object EngineTick extends TradingEngineCommand

// For internal use
case class BootEvents(events: Seq[TradingEngineEvent]) extends TradingEngineCommand
