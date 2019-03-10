package flashbot.core

import akka.NotUsed
import akka.stream.scaladsl.Source
import flashbot.models.core.Action.ActionQueue
import flashbot.models.core.{Market, Portfolio}

trait TradingSession {
  def id: String
  def send(events: Any*): Unit
  def getPortfolio: Portfolio
  def getActionQueues: Map[String, ActionQueue]
  def getPrices: PriceIndex
  def getInstruments: InstrumentIndex
  def getExchangeParams: Map[String, ExchangeParams]

  // A weak reference to this iteration of the session. It must be GC'd after the current
  // iteration is done. Therefore, DO NOT HOLD ON TO THIS REFERENCE! Instead, use it as
  // the key in WeakHashMaps for caching computations in between Strategy mixins.
  protected[flashbot] def ref: java.lang.Long

  def tryRound(market: Market, size: FixedSize): Option[FixedSize]
  def round(market: Market, size: FixedSize): FixedSize =
    tryRound(market, size).getOrElse({
      throw new RuntimeException(s"Can't round $size for market $market")
    })
}

object TradingSession {
  case class SessionSetup(instruments: InstrumentIndex,
                          exchanges: Map[String, Exchange],
                          strategy: Strategy[_],
                          sessionId: String,
                          streams: Seq[Source[MarketData[_], NotUsed]],
                          sessionMicros: Long)

  def closeActionForOrderId(actions: ActionQueue, ids: IdManager, id: String): ActionQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId).contains(id) =>
        actions.closeActive
      case _ => actions
    }

  implicit def implicitInstruments(implicit ctx: TradingSession): InstrumentIndex = ctx.getInstruments
  implicit def implicitPrices(implicit ctx: TradingSession): PriceIndex = ctx.getPrices
}
