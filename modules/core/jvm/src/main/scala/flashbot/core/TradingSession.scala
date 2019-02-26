package flashbot.core

import akka.NotUsed
import akka.stream.scaladsl.Source
import flashbot.models.core.Action.ActionQueue
import flashbot.models.core.Portfolio

trait TradingSession {
  def id: String
  def send(events: Any*): Unit
  def getPortfolio: Portfolio
  def getActionQueues: Map[String, ActionQueue]
  def getPrices: PriceIndex
  def getInstruments: InstrumentIndex
  def getExchangeParams: Map[String, ExchangeParams]
}

object TradingSession {
  case class SessionSetup(instruments: InstrumentIndex,
                          exchanges: Map[String, Exchange],
                          strategy: Strategy[_],
                          sessionId: String,
                          streams: Seq[Source[MarketData[_], NotUsed]],
                          sessionMicros: Long,
                          initialPortfolio: Portfolio)

  def closeActionForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId).contains(id) =>
        actions.closeActive
      case _ => actions
    }
}
