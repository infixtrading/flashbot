package com.infixtrading.flashbot.models.api

import com.infixtrading.flashbot.core.FlashbotConfig.BotConfig
import com.infixtrading.flashbot.core.TradingSessionMode
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.report.ReportEvent

import scala.concurrent.duration.Duration

sealed trait TradingEngineCommand

case class ConfigureBot(id: String, config: BotConfig) extends TradingEngineCommand

case class EnableBot(id: String) extends TradingEngineCommand
case class DisableBot(id: String) extends TradingEngineCommand
case class BotHeartbeat(id: String) extends TradingEngineCommand

case class ProcessBotReportEvent(botId: String, event: ReportEvent) extends TradingEngineCommand

case object EngineTick extends TradingEngineCommand
