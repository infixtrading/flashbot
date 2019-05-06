package flashbot.models

import flashbot.core.{Report, ReportEvent}
import io.circe.Json

case class TradingSessionState(id: String,
                               strategy: String,
                               strategyParams: Json,
                               mode: TradingSessionMode,
                               startedAt: Long,
                               report: Report) {
  def updateReport(event: ReportEvent): TradingSessionState = {
    report.update(event)
    this
  }
}
