package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.DeltaFmt.HasUpdateEvent
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.models.core.Order.{Buy, Sell, Side}

import scala.collection.immutable.{Queue, TreeMap}
import OrderBook._
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.circe.generic.JsonCodec
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.parser._

@JsonCodec
case class OrderBook(orders: Map[String, Order] = Map.empty,
                     asks: Asks = Asks.empty,
                     bids: Bids = Bids.empty,
                     lastUpdate: Option[Delta] = None)
      extends HasUpdateEvent[OrderBook, Delta] {

  def isInitialized: Boolean = orders.nonEmpty

  override protected def withLastUpdate(d: Delta): OrderBook =
    copy(lastUpdate = Some(d))

  override protected def step(event: Delta): OrderBook = event match {
    case Open(orderId, price, size, side) =>
      _open(orderId, price, size, side)
    case Done(orderId) =>
      _done(orderId)
    case Change(orderId, newSize) =>
      _change(orderId, newSize)
  }

  def open(order: Order): OrderBook = open(order.id, order.price.get, order.amount, order.side)
  def open(id: String, price: Double, size: Double, side: Side): OrderBook = update(Open(id, price, size, side))
  def done(id: String): OrderBook = update(Done(id))
  def change(id: String, newSize: Double): OrderBook = update(Change(id, newSize))

  private def _open(id: String, price: Double, size: Double, side: Side): OrderBook = {
    val o = Order(id, side, size, Some(price))
    val newOrders = orders + (id -> o)
    side match {
      case Sell => copy(
        orders = newOrders,
        asks = Asks(addToIndex(asks.index, o)))
      case Buy => copy(
        orders = newOrders,
        bids = Bids(addToIndex(bids.index, o)))
    }
  }

  private def _done(id: String): OrderBook =
    orders get id match {
      case Some(o@Order(_, Sell, _, _)) => copy(
        orders = orders - id,
        asks = Asks(rmFromIndex(asks.index, o)))
      case Some(o@Order(_, Buy, _, _)) => copy(
        orders = orders - id,
        bids = Bids(rmFromIndex(bids.index, o)))
      case None => this // Ignore "done" messages for orders not in the book
    }

  private def _change(id: String, newSize: Double): OrderBook = {
    orders(id) match {
      case o@Order(_, Sell, _, Some(price)) => copy(
        orders = orders + (id -> o.copy(amount = newSize)),
        asks = Asks(asks.index + (price -> (asks.index(price).filterNot(_ == o) :+ o.copy(amount = newSize)))))
      case o@Order(_, Buy, _, Some(price)) => copy(
        orders = orders + (id -> o.copy(amount = newSize)),
        bids = Bids(bids.index + (price -> (bids.index(price).filterNot(_ == o) :+ o.copy(amount = newSize)))))
    }
  }

  def spread: Option[Double] = {
    if (asks.index.nonEmpty && bids.index.nonEmpty) {
      if (asks.index.firstKey > asks.index.lastKey) {
        throw new RuntimeException("Asks out of order")
      }
      if (bids.index.firstKey < bids.index.lastKey) {
        throw new RuntimeException("Bids out of order")
      }
      Some(asks.index.firstKey - bids.index.firstKey)
    } else None
  }

  def fill(side: Side, quantity: Double, limit: Option[Double] = None): (Seq[(Double, Double)], OrderBook) = {
    val ladder: OrderIndex = if (side == Buy) asks else bids
    // If there is nothing to match against, return.
    if (ladder.index.isEmpty) {
      (Seq.empty, this)
    } else {
      val (bestPrice, orderQueue) = ladder.index.head
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


  implicit val doubleKeyEncoder: KeyEncoder[Double] = KeyEncoder.instance(_.toString)
  implicit val doubleKeyDecoder: KeyDecoder[Double] = KeyDecoder.instance(x => Some(x.toDouble))

  sealed trait OrderIndex {
    def index: TreeMap[Double, Queue[Order]]
  }

  case class Asks(index: TreeMap[Double, Queue[Order]]) extends OrderIndex

  object Asks {
    def empty: Asks = Asks(TreeMap.empty)

    implicit val asksEn: Encoder[Asks] = Encoder.encodeJsonObject.contramapObject(
      (asks: Asks) => asks.index.asJsonObject)
    implicit val asksDe: Decoder[Asks] = Decoder.decodeJsonObject.map(obj =>
      obj.keys.foldLeft(Asks.empty)((memo, key) =>
        memo.copy(index = memo.index + (key.toDouble ->
          obj(key).get.as[Queue[Order]].right.get))))


  }

  case class Bids(index: TreeMap[Double, Queue[Order]]) extends OrderIndex
  object Bids {
    def empty: Bids = Bids(TreeMap.empty(Ordering.by(-_)))

    implicit val bidsEn: Encoder[Bids] = Encoder.encodeJsonObject.contramapObject(
      (bids: Bids) => bids.index.asJsonObject)
    implicit val bidsDe: Decoder[Bids] = Decoder.decodeJsonObject.map(obj =>
      obj.keys.foldLeft(Bids.empty)((memo, key) =>
        memo.copy(index = memo.index + (key.toDouble ->
          obj(key).get.as[Queue[Order]].right.get))))

  }


  @JsonCodec sealed trait Delta
  case class Open(orderId: String, price: Double, size: Double, side: Side) extends Delta
  case class Done(orderId: String) extends Delta
  case class Change(orderId: String, newSize: Double) extends Delta

  object Delta {
    def fromOrderEventOpt(event: OrderEvent): Option[Delta] = event match {
      case OrderOpen(orderId, _, price, size, side) =>
        Some(Open(orderId, price, size, side))
      case OrderDone(orderId, _, _, _, _, _) =>
        Some(Done(orderId))
      case OrderChange(orderId, _, _, newSize) =>
        Some(Change(orderId, newSize))
      case _ => None
    }
  }

  val foldOrderBook: (OrderBook, OrderBook) => OrderBook = (a: OrderBook, b: OrderBook) =>
    b.orders.values.foldLeft(a)((memo, order) =>
        memo.open(order.id, order.price.get, order.amount, order.side))

  val unfoldOrderBook: OrderBook => (OrderBook, Option[OrderBook]) = (book: OrderBook) => {
    if (book.orders.values.size > 1) {
      val order = book.orders.values.head
      (book.done(order.id), Some(OrderBook().open(order)))
    } else (book, None)
  }

  // This has to be a def!
  implicit def orderBookFmt: DeltaFmtJson[OrderBook] =
    DeltaFmt.updateEventFmtJsonWithFold[OrderBook, Delta]("book",
      foldOrderBook, unfoldOrderBook)

}
