package com.infixtrading.flashbot.models

import akka.remote.serialization.ProtobufSerializer

sealed trait TradingEngineCommand
case class StartTradingSession(botId: Option[String],
                               strategyKey: String,
                               strategyParams: Json,
                               mode: Mode,
                               sessionEvents: ActorRef,
                               initialPortfolio: Portfolio,
                               report: Report) extends TradingEngineCommand

case object StartEngine extends TradingEngineCommand
case class ProcessBotSessionEvent(botId: String, event: ReportEvent) extends TradingEngineCommand

