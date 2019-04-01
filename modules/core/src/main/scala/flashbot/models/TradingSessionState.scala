package flashbot.models

import flashbot.core.{Report, ReportDelta}
import io.circe.Json

case class TradingSessionState(id: String,
                               strategy: String,
                               strategyParams: Json,
                               mode: TradingSessionMode,
                               startedAt: Long,
                               portfolio: Portfolio,
                               report: Report) {
  def updateReport(delta: ReportDelta): TradingSessionState =
    copy(report = report.update(delta))
}
