package flashbot.core
import flashbot.models.core.StrategyEvent
import OrderManagement._
import akka.Done

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
  * Order lifecycle:
  *   1. In-flight
  *   2. Received
  *   3. Working
  *     a) Consistent
  *     b) Hung
  *   4. Done
  *     a) Complete
  *     b) Cancelled
  *
  * orderManager.put(ContinuousQuote("btc_usd", .4, 3500, Buy))
  */
trait OrderManagement extends DataHandler with EventHandler {
  self: Strategy[_] =>

  abstract override def aroundHandleEvent(event: StrategyEvent)(implicit ctx: TradingSession) = {
  }

  abstract override def aroundHandleData(data: MarketData[_])(implicit ctx: TradingSession) = {
  }

  /**
    * Override this to control which orders should exist in the system at the current moment.
    * This is invoked after handleData and handleEvent, so the result of this function can
    * depend on in-memory state updated from those functions.
    */
  def declareOrders(): List[OrderRef]
}

object OrderManagement {

}
