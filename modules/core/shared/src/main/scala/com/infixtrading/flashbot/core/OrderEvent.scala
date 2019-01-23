package com.infixtrading.flashbot.core
import com.infixtrading.flashbot.models.core.Order
import com.infixtrading.flashbot.models.core.Order._

sealed trait OrderEvent {
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
    case (Some(_), Some(_)) => Limit
    case (None, None) => Order.Market
  }
}

final case class OrderChange(orderId: String,
                             product: String,
                             price: Option[Double],
                             newSize: Double) extends OrderEvent {
  def orderType: OrderType = if (price.isDefined) Limit else Order.Market
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
