package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.{OrderChange, OrderDone, OrderEvent, OrderOpen}
import com.infixtrading.flashbot.models.core.Order.{Buy, Sell, Side}

import scala.collection.immutable.{Queue, TreeMap}

case class OrderBook(orders: Map[String, Order] = Map.empty,
                     asks: TreeMap[Double, Queue[Order]] = TreeMap.empty,
                     bids: TreeMap[Double, Queue[Order]] = TreeMap.empty(Ordering.by(-_))) {

  def isInitialized: Boolean = orders.nonEmpty

  def processOrderEvent(event: OrderEvent): OrderBook = event match {
    case OrderOpen(orderId, p, price, size, side) => open(orderId, price, size, side)
    case OrderDone(orderId, _, _, _, _, _) => done(orderId)
    case OrderChange(orderId, _, _, newSize) => change(orderId, newSize)
    case _ => this
  }

  def open(id: String, price: Double, size: Double, side: Side): OrderBook = {
    val o = Order(id, side, size, Some(price))
    val newOrders = orders + (id -> o)
    side match {
      case Sell => copy(
        orders = newOrders,
        asks = addToIndex(asks, o))
      case Buy => copy(
        orders = newOrders,
        bids = addToIndex(bids, o))
    }
  }

  def done(id: String): OrderBook =
    orders get id match {
      case Some(o@Order(_, Sell, _, _)) => copy(
        orders = orders - id,
        asks = rmFromIndex(asks, o))
      case Some(o@Order(_, Buy, _, _)) => copy(
        orders = orders - id,
        bids = rmFromIndex(bids, o))
      case None => this // Ignore "done" messages for orders not in the book
    }

  def change(id: String, newSize: Double): OrderBook = {
    orders(id) match {
      case o@Order(_, Sell, _, Some(price)) => copy(
        orders = orders + (id -> o.copy(amount = newSize)),
        asks = asks + (price -> (asks(price).filterNot(_ == o) :+ o.copy(amount = newSize))))
      case o@Order(_, Buy, _, Some(price)) => copy(
        orders = orders + (id -> o.copy(amount = newSize)),
        bids = bids + (price -> (bids(price).filterNot(_ == o) :+ o.copy(amount = newSize))))
    }
  }

  def spread: Option[Double] = {
    if (asks.nonEmpty && bids.nonEmpty) {
      if (asks.firstKey > asks.lastKey) {
        throw new RuntimeException("Asks out of order")
      }
      if (bids.firstKey < bids.lastKey) {
        throw new RuntimeException("Bids out of order")
      }
      Some(asks.firstKey - bids.firstKey)
    } else None
  }

  def fill(side: Side, quantity: Double, limit: Option[Double] = None): (Seq[(Double, Double)], OrderBook) = {
    val ladder = if (side == Buy) asks else bids
    // If there is nothing to match against, return.
    if (ladder.isEmpty) {
      (Seq.empty, this)
    } else {
      val (bestPrice, orderQueue) = ladder.head
      val isMatch = limit.forall(lim => if (side == Buy) lim >= bestPrice else lim <= bestPrice)
      if (isMatch) {
        // If there is a match, generate the fill, and recurse.
        val topOrder = orderQueue.head
        val filledQuantity = math.min(quantity, topOrder.amount)
        val updatedBook =
          if (filledQuantity == topOrder.amount) this.done(topOrder.id)
          else this.change(topOrder.id, topOrder.amount - filledQuantity)
        val remainder = quantity - filledQuantity
        if (remainder > 0) {
          val (recursedFills, recursedBook) = updatedBook.fill(side, remainder, limit)
          ((topOrder.price.get, filledQuantity) +: recursedFills, recursedBook)
        } else {
          (Seq((topOrder.price.get, filledQuantity)), updatedBook)
        }
      } else {
        // If no match, do nothing.
        (Seq.empty, this)
      }
    }
  }

  private def addToIndex(idx: TreeMap[Double, Queue[Order]], o: Order): TreeMap[Double, Queue[Order]] =
    idx + (o.price.get -> (idx.getOrElse[Queue[Order]](o.price.get, Queue.empty) :+ o))

  private def rmFromIndex(idx: TreeMap[Double, Queue[Order]], o: Order):
  TreeMap[Double, Queue[Order]] = {
    val os = idx(o.price.get).filterNot(_ == o)
    if (os.isEmpty) idx - o.price.get else idx + (o.price.get -> os)
  }
}

object OrderBook {
  final case class SnapshotOrder(product: String,
                                 seq: Long,
                                 bid: Boolean,
                                 id: String,
                                 price: Double,
                                 size: Double)
}
