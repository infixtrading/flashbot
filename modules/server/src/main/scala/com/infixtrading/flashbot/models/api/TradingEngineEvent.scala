package com.infixtrading.flashbot.models.api
import com.infixtrading.flashbot.core.TradingSessionMode
import com.infixtrading.flashbot.models.core.{Account, Market, Portfolio, Position}
import com.infixtrading.flashbot.report.{Report, ReportDelta}
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

case class EngineStarted(micros: Long, withBots: Seq[String]) extends TradingEngineEvent

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

