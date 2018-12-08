package com.infixtrading.flashbot.engine

import akka.NotUsed
import akka.stream.scaladsl.Source
import io.circe.Json
import com.infixtrading.flashbot.core.Action.ActionQueue
import com.infixtrading.flashbot.core.DataSource._
import com.infixtrading.flashbot.core._


trait TradingSession {
  def id: String
  def send(events: Any*): Unit
  def getPortfolio: Portfolio
  def getActionQueues: Map[String, ActionQueue]
  def getPrices: PriceIndex
  def instruments: InstrumentIndex
}

object TradingSession {

  trait Event
  case class LogMessage(message: String) extends Event
  case class OrderTarget(market: Market,
                         key: String,
                         size: FixedSize,
                         price: Option[Double],
                         once: Option[Boolean] = None,
                         postOnly: Option[Boolean] = None) extends Event {
    def id: String = s"$market:$key"
  }
  case class SessionReportEvent(event: ReportEvent) extends Event


  sealed trait Mode
  case class Backtest(range: TimeRange) extends Mode
  case object Paper extends Mode
  case object Live extends Mode

  object Mode {
    def apply(str: String): Mode = str match {
      case "live" => Live
      case "paper" => Paper
    }
  }

  case class TradingSessionState(id: String,
                                 strategy: String,
                                 strategyParams: Json,
                                 mode: Mode,
                                 startedAt: Long,
                                 portfolio: Portfolio,
                                 report: Report) {
    def updateReport(delta: ReportDelta): TradingSessionState =
      copy(report = report.update(delta))
  }

  case class SessionSetup(instruments: InstrumentIndex,
                          exchanges: Map[String, Exchange],
                          strategy: Strategy,
                          sessionId: String,
                          streams: Seq[Source[MarketData[_], NotUsed]],
                          sessionMicros: Long)

  def closeActionForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId) == id =>
        actions.closeActive
      case _ => actions
    }
}
