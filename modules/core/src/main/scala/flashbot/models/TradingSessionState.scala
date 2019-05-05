package flashbot.models

import flashbot.core.{Report, ReportDelta}
import io.circe.Json

case class TradingSessionState(id: String,
                               strategy: String,
                               strategyParams: Json,
                               mode: TradingSessionMode,
                               startedAt: Long,
                               report: Report) {
  def updateReport(delta: ReportDelta): TradingSessionState = {
    report.update(delta)
    this
  }

//  def updatePortfolio(delta: PortfolioDelta): TradingSessionState = {
//    portfolio.update(delta)
//    this
//  }
}
