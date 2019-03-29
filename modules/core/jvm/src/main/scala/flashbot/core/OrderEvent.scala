package flashbot.core

import flashbot.core.{Timestamped, Trade}
import flashbot.models.api.TradingSessionEvent
import flashbot.models.core.Order._

sealed trait OrderEvent extends TradingSessionEvent {
  val orderId: String
  val product: String
}

final case class OrderOpen(orderId: String,
                           product: String,
                           price: Double,
                           size: Double,
                           side: Side) extends OrderEvent

final case class OrderDone(orderId: String,
                           product: String,
                           side: Side,
                           reason: DoneReason,
                           price: Option[Double],
                           remainingSize: Option[Double]) extends OrderEvent {
  def orderType: OrderType = (price, remainingSize) match {
    case (Some(_), Some(_)) => LimitOrder
    case (None, None) => Order.MarketOrder
  }
}

final case class OrderChange(orderId: String,
                             product: String,
                             price: Option[Double],
                             newSize: Double) extends OrderEvent {
  def orderType: OrderType = if (price.isDefined) LimitOrder else Order.MarketOrder
}

final case class OrderMatch(tradeId: Long,
                            product: String,
                            micros: Long,
                            size: Double,
                            price: Double,
                            direction: TickDirection,
                            makerOrderId: String,
                            orderId: String) extends OrderEvent {
  def toTrade: Trade = Trade(tradeId.toString, micros, price, size, direction)
}

final case class OrderReceived(orderId: String,
                               product: String,
                               clientOid: Option[String],
                               `type`: OrderType) extends OrderEvent {
}


sealed trait DoneReason
case object Canceled extends DoneReason
case object Filled extends DoneReason

object DoneReason {
  def parse(str: String): DoneReason = str match {
    case "canceled" => Canceled
    case "filled" => Filled
  }
}

trait RawOrderEvent extends Timestamped {
  def toOrderEvent: OrderEvent
}
