package com.infixtrading.flashbot.models.api

import akka.actor.ActorRef
import com.infixtrading.flashbot.core.TradingSessionMode
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.report.{Report, ReportEvent}
import io.circe.Json

import scala.concurrent.duration.Duration

sealed trait TradingEngineCommand

case class ConfigureBot(id: String,
                        strategyKey: String,
                        strategyParams: String,
                        mode: TradingSessionMode,
                        ttl: Option[Duration],
                        initialPortfolio: Portfolio) extends TradingEngineCommand

case class EnableBot(id: String) extends TradingEngineCommand
case class DisableBot(id: String) extends TradingEngineCommand
case class BotHeartbeat(id: String) extends TradingEngineCommand

case class ProcessBotSessionEvent(botId: String, event: ReportEvent) extends TradingEngineCommand

