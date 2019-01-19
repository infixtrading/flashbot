package com.infixtrading.flashbot.models.api
import com.infixtrading.flashbot.core.StrategyInfo
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.report.Report

sealed trait TradingEngineResponse
final case class GenericResponse[T](a: T) extends TradingEngineResponse
sealed trait BuiltInResponse extends TradingEngineResponse

case class Pong(startedAt: Long) extends BuiltInResponse
case class ReportResponse(report: Report) extends BuiltInResponse
case class BotResponse(id: String, reports: Seq[Report]) extends BuiltInResponse
case class BotsResponse(bots: Seq[BotResponse]) extends BuiltInResponse
case class BotSessionsResponse(id: String, sessions: Seq[TradingSessionState]) extends BuiltInResponse
case class StrategyResponse(name: String) extends BuiltInResponse
case class StrategiesResponse(strats: Seq[StrategyResponse]) extends BuiltInResponse
case class StrategyInfoResponse(title: String, key: String,
                                info: Option[StrategyInfo]) extends BuiltInResponse
case class PortfolioResponse(portfolio: Portfolio) extends BuiltInResponse
