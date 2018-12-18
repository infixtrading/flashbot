package com.infixtrading.flashbot.models.api

import akka.actor.ActorRef
import com.infixtrading.flashbot.core.TradingSessionMode
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.report.{Report, ReportEvent}
import io.circe.Json

sealed trait TradingEngineCommand

case class ConfigureBot(id: String,
                        strategyKey: String,
                        strategyParams: String,
                        mode: TradingSessionMode,
                        initialPortfolio: Portfolio) extends TradingEngineCommand

case class StartBot(id: String) extends TradingEngineCommand

case class ProcessBotSessionEvent(botId: String, event: ReportEvent) extends TradingEngineCommand

