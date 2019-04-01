package flashbot.core

import flashbot.models.{Order, RejectedReason, TradingSessionEvent}
import flashbot.models.Order._

sealed trait OrderEvent extends Tick {
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

final case class OrderMatch(tradeId: String,
                            product: String,
                            micros: Long,
                            size: Double,
                            price: Double,
                            direction: TickDirection,
                            makerOrderId: String,
                            takerOrderId: String) extends OrderEvent {
  override val orderId = takerOrderId
  def toTrade: Trade = Trade(tradeId, micros, price, size, direction)
}

final case class OrderReceived(orderId: String,
                               product: String,
                               clientOid: String,
                               `type`: OrderType) extends OrderEvent {
}

sealed trait OrderError extends Exception with OrderEvent

final case class OrderRejected(orderId: String,
                               product: String,
                               reason: RejectedReason,
                               cause: Throwable) extends OrderError

final case class CancelError(orderId: String,
                             product: String,
                             cause: Throwable) extends OrderError

final case class OrderException(orderId: String,
                                product: String,
                                cause: Throwable) extends OrderError



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
