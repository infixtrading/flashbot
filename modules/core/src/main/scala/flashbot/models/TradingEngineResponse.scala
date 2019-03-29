package flashbot.models.api
import java.time.Instant

import flashbot.core.{Report, StrategyInfo}
import flashbot.models.core.Portfolio

sealed trait TradingEngineResponse
final case class GenericResponse[T](a: T) extends TradingEngineResponse
sealed trait BuiltInResponse extends TradingEngineResponse

case class Pong(startedAtMicros: Long) extends BuiltInResponse {
  def startedAt: Instant = Instant.ofEpochMilli(startedAtMicros / 1000)
}
case class ReportResponse(report: Report) extends BuiltInResponse
case class BotResponse(id: String, reports: Seq[Report]) extends BuiltInResponse
case class BotsResponse(bots: Seq[BotResponse]) extends BuiltInResponse
case class BotSessionsResponse(id: String, sessions: Seq[TradingSessionState]) extends BuiltInResponse
case class StrategyResponse(name: String) extends BuiltInResponse
case class StrategiesResponse(strats: Seq[StrategyResponse]) extends BuiltInResponse
case class StrategyInfoResponse(title: String, key: String, info: StrategyInfo) extends BuiltInResponse
case class PortfolioResponse(portfolio: Portfolio) extends BuiltInResponse
