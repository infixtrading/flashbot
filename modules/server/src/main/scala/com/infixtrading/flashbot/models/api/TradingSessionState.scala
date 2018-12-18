package com.infixtrading.flashbot.models.api
import com.infixtrading.flashbot.core.TradingSessionMode
import com.infixtrading.flashbot.models.core.Portfolio
import com.infixtrading.flashbot.report.{Report, ReportDelta}
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
