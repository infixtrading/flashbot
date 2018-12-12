package com.infixtrading.flashbot.models.api

import akka.actor.ActorRef
import com.infixtrading.flashbot.core.TradingSessionMode
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.report.{Report, ReportEvent}
import io.circe.Json

sealed trait TradingEngineCommand

case class StartTradingSession(botId: Option[String],
                               strategyKey: String,
                               strategyParams: Json,
                               mode: TradingSessionMode,
                               sessionEvents: ActorRef,
                               initialPortfolio: Portfolio,
                               report: Report) extends TradingEngineCommand

case object StartEngine extends TradingEngineCommand
case class ProcessBotSessionEvent(botId: String, event: ReportEvent) extends TradingEngineCommand

