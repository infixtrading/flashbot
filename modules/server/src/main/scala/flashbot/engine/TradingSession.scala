package flashbot.engine

import akka.NotUsed
import akka.stream.scaladsl.Source
import io.circe.Json
import flashbot.core.DataSource._
import flashbot.core._
import flashbot.report.{Report, ReportDelta, ReportEvent}
import flashbot.core.{IdManager, InstrumentIndex, MarketData, PriceIndex}
import flashbot.models.core.Action.ActionQueue
import flashbot.models.core.Portfolio

trait TradingSession {
  def id: String
  def send(events: Any*): Unit
  def getPortfolio: Portfolio
  def getActionQueues: Map[String, ActionQueue]
  def getPrices: PriceIndex
  def instruments: InstrumentIndex
}

object TradingSession {
  case class SessionSetup(instruments: InstrumentIndex,
                          exchanges: Map[String, Exchange],
                          strategy: Strategy,
                          sessionId: String,
                          streams: Seq[Source[MarketData[_], NotUsed]],
                          sessionMicros: Long,
                          initialPortfolio: Portfolio)

  def closeActionForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId) == id =>
        actions.closeActive
      case _ => actions
    }
}
