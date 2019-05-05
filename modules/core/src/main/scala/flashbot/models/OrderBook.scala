package flashbot.models

import flashbot.core.DeltaFmt.HasUpdateEvent
import flashbot.core._
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.models.OrderBook.OrderBookSide.FifoIterator
import flashbot.models.OrderBook.{Change, Delta, Done, FIFOQueue, Open, OrderBookSide}
import flashbot.util.NumberUtils
import io.circe.generic.JsonCodec
import io.circe.{Decoder, Encoder}
import it.unimi.dsi.fastutil.doubles.{Double2ObjectRBTreeMap, DoubleComparators}
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap
import spire.syntax.cfor._

import scala.collection.JavaConverters._
import scala.util.Random

/**
  * A mutable order book.
  */
class OrderBook(val tickSize: Double,

                // Index of id -> order.
                protected[flashbot] var orders: Object2ObjectOpenHashMap[String, Order] = null,

                // Price ascending price levels for asks, with a queue of orders at each level.
                // The LinkedHashMap allows us to maintain insertion order while preserving
                // constant time order cancellation.
                protected[flashbot] var asks: OrderBookSide = null,

                // Same as the asks data structure, but the ordering of the price levels is reversed.
                protected[flashbot] var bids: OrderBookSide = null,

                // Used for streaming.
                protected var lastUpdate: Option[Delta] = None)

      extends HasUpdateEvent[OrderBook, Delta] with Matching {

  // Initialize
  if (orders == null) orders = new Object2ObjectOpenHashMap()
  if (asks == null) asks = OrderBookSide.newAsks(tickSize)
  if (bids == null) bids = OrderBookSide.newBids(tickSize)

  // Validate
  assert(orders.isEmpty)
  assert(asks.index.isEmpty)
  assert(bids.index.isEmpty)
  assert(lastUpdate.isEmpty)

  def getLastUpdate: Option[Delta] = lastUpdate

  // Sizes
  private var askCount = 0
  private var bidCount = 0
  def size: Int = askCount + bidCount

  // Iterators and streams
  def asksIterator: Iterator[Order] =
    asks.index.values.stream.flatMap[Order](_.values.stream).iterator.asScala
  def bidsIterator: Iterator[Order] =
    bids.index.values.stream.flatMap[Order](_.values.stream).iterator.asScala
  def iterator: Iterator[Order] = bidsIterator ++ asksIterator
  def stream: Stream[Order] = iterator.toStream

  // Arrays
  private var _asksArray: Array[Order] = _
  private var _bidsArray: Array[Order] = _
  private val _emptyArray: Array[Order] = Array.empty[Order]

  def asksArray: Array[Order] = {
    if (_asksArray eq null) {
      if (askCount == 0) {
        _asksArray = _emptyArray
      } else {
        _asksArray = new Array[Order](askCount)
        val it = asksIterator
        cfor(0)(_ < askCount, _ + 1) { i =>
          _asksArray(i) = it.next()
        }
      }
    }
    _asksArray
  }

  def bidsArray: Array[Order] = {
    if (_bidsArray eq null) {
      if (bidCount == 0) {
        _bidsArray = _emptyArray
      } else {
        _bidsArray = new Array[Order](bidCount)
        val it = bidsIterator
        cfor(0)(_ < bidCount, _ + 1) { i =>
          _bidsArray(i) = it.next()
        }
      }
    }
    _bidsArray
  }

//  override def equals(obj: Any) = obj match {
//    case book: OrderBook if size == book.size =>
//      stream.zip(book.stream).forall {
//        case (o1, o2) => o1 == o2
//      }
//    case _ => false
//  }

//  override def hashCode() = {
//    val b = new mutable.StringBuilder()
//    orders.foreachKey(b.append)
//    b.mkString.hashCode
//  }

  def isInitialized: Boolean = !orders.isEmpty

//  override protected def withLastUpdate(d: Delta): OrderBook = {
//    this.lastUpdate = Some(d)
//    this
//  }

  override protected def _step(event: Delta): OrderBook = event match {
    case Open(orderId, price, size, side) =>
      open(orderId, price, size, side)
    case Done(orderId) =>
      done(orderId)
    case Change(orderId, newSize) =>
      change(orderId, newSize)
  }

//  protected def _open(order: Order): OrderBook = _open(order.id, order.price.get, order.amount, order.side)
//  protected def _open(id: String, price: Num, size: Num, side: Side): OrderBook = update(Open(id, price, size, side))
//  protected def _done(id: String): OrderBook = update(Done(id))
//  protected def _change(id: String, newSize: Num): OrderBook = update(Change(id, newSize))

  def open(order: Order): OrderBook = {
    if (orders.containsKey(order.id))
      throw new RuntimeException(s"OrderBook already contains order ${order.id}")

    // Update the index first.
    orders.put(order.id, order)

    // Then depending on the side, ensure the correct queue exists.
    // Also update the relevant count.
    val queue = order.side match {
      case Sell =>
        askCount += 1
        _asksArray = null
        var curAsks: Object2ObjectLinkedOpenHashMap[String, Order] = asks.index.get(order.price.get)
        if (curAsks == null) {
          curAsks = new Object2ObjectLinkedOpenHashMap[String, Order]
          asks.index.put(order.price.get, curAsks)
        }
        curAsks
      case Buy =>
        bidCount += 1
        _bidsArray = null
        var curBids: Object2ObjectLinkedOpenHashMap[String, Order] = bids.index.get(order.price.get)
        if (curBids == null) {
          curBids = new Object2ObjectLinkedOpenHashMap[String, Order]
          bids.index.put(order.price.get, curBids)
        }
        curBids
    }

    // Add the order
    queue.put(order.id, order)
    this
  }

  def open(id: String, price: Double, size: Double, side: Side): OrderBook =
    open(Order(id, side, size, Some(price)))

  def done(id: String): OrderBook = {
    // Look up the order by it's id and remove it.
    var o = orders.get(id)

    // Ignore "done" messages for orders not in the book
    if (o != null) {
      this._removeOrderId(id, o.side)
    }

    this
  }

  def change(id: String, newSize: Double): OrderBook = {
    // Must be a valid order
    orders(id).setAmount(newSize)
    this
  }

  private def _removeOrderId(id: String, side: Side): OrderBook = {
    val o = this.orders(id)
    this.orders.remove(id)

    val index: OrderBookSide = side match {
      case Sell =>
        askCount -= 1
        _asksArray = null
        asks
      case Buy =>
        bidCount -= 1
        _bidsArray = null
        bids
    }

    val price = o.price.get
    val queue = index.index.get(price)
    queue.remove(id)

    // If the queue is now empty, remove that price level completely.
    if (queue.isEmpty) {
      index.index.remove(price)
    }

    this
  }

  def spread: Double =
    if (!asks.index.isEmpty && !bids.index.isEmpty)
      asks.index.firstDoubleKey() - bids.index.firstDoubleKey()
    else java.lang.Double.NaN

  def ordersAtPriceIterator(price: Double): FifoIterator = {
    val orders = ordersAtPrice(price)
    if (orders != null) new FifoIterator(orders) else FifoIterator.empty
  }

  def ordersAtPrice(price: Double): FIFOQueue =
    if (asks.index.containsKey(price)) asks.index.get(price)
    else if (bids.index.containsKey(price)) bids.index.get(price)
    else null

  def quoteSideOfPrice(price: Double): QuoteSide =
    if (asks.nonEmpty && price >= asks.bestPrice) Ask
    else if (bids.nonEmpty && price <= bids.bestPrice) Bid
    else null

  // Returns the unfilled remainder
//  def fill(side: Side, quantity: Double, limit: Option[Double] = None): Double = {
//
//    val ladder = if (side == Buy) asks else bids
//    if (!ladder.isEmpty) {
//      val bestPrice = ladder.firstDoubleKey()
//      val orderQueue = ladder.get(bestPrice)
//      val isMatch = limit.forall(lim =>
//        if (side == Buy) lim >= bestPrice else lim <= bestPrice)
//
//      if (isMatch) {
//        // If there is a match, generate the fill, and recurse.
//        val topOrder = orderQueue.removeFirst()
//        val filledQuantity = quantity min topOrder.amount
//        val thisFill = (topOrder.price.get, filledQuantity)
//
//        // Update the order with the new amount.
//        topOrder.setAmount(topOrder.amount - filledQuantity)
//
//        // If the updated order is 0, remove it.
//        if (topOrder.amount == 0) {
//          this.done(topOrder.id)
//        }
//
//        // If the remainder is over 0 and the top order was removed, recurse.
//        val remainder = NumberUtils.round8(quantity - filledQuantity)
//        if (remainder > 0 && topOrder.amount == 0) {
//          return thisFill :: this.fill(side, remainder, limit)
//        }
//
//        // Otherwise, return only this fill.
//        return List(thisFill)
//      }
//    }
//
//    // Fallback to returning an empty list of fills
//    Nil
//  }

  /**
    * Infers the tick size of the order book by finding the minimum distance between prices.
    */
//  def tickSize: Double = ???

  val random = new Random()
  def genID: String = {
    var id = random.nextLong().toString
    while (!orders.containsKey(id)) {
      id = random.nextLong().toString
    }
    id
  }

  def sideOf(qs: QuoteSide): OrderBookSide = if (qs == Ask) asks else bids

  override def matchMutable(quoteSide: QuoteSide,
                            approxPriceLimit: Double,
                            approxSize: Double): Double =
    sideOf(quoteSide).matchMutable(quoteSide, approxPriceLimit, approxSize)

  override def matchSilent(quoteSide: QuoteSide,
                           approxPriceLimit: Double,
                           approxSize: Double): Double =
    sideOf(quoteSide).matchSilent(quoteSide, approxPriceLimit, approxSize)

  override def matchSilentAvg(quoteSide: QuoteSide,
                              approxPriceLimit: Double,
                              approxSize: Double): (Double, Double) =
    sideOf(quoteSide).matchSilentAvg(quoteSide, approxPriceLimit, approxSize)

  def priceIterator: Iterator[java.lang.Double] =
    bids.priceIterator ++ asks.priceIterator
}

object OrderBook {

  def apply(tickSize: Double): OrderBook = new OrderBook(tickSize)

  final case class SnapshotOrder(product: String,
                                 seq: Long,
                                 bid: Boolean,
                                 id: String,
                                 price: Double,
                                 size: Double)

  @JsonCodec
  case class OrderBookJson(tickSize: Double, orders: Seq[Order])

  implicit val orderBookEncoder: Encoder[OrderBook] =
    implicitly[Encoder[OrderBookJson]].contramap(book =>
      OrderBookJson(book.tickSize, book.iterator.toSeq))

  implicit val orderBookDecoder: Decoder[OrderBook] =
    implicitly[Decoder[OrderBookJson]].map(json =>
      json.orders.foldLeft(new OrderBook(json.tickSize))(_ open _))

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

  // Build up the left order book with the orders of the right one. Discard the right book.
  val foldOrderBook: (OrderBook, OrderBook) => OrderBook =
    (a: OrderBook, b: OrderBook) =>
      b.iterator.foldLeft(a)(_ open _)

  def _remove(book: OrderBook, order: Order): Order = {
    book.done(order.id)
    order
  }

  private val bookBatchSize = 50
  val unfoldOrderBook: OrderBook => (OrderBook, Option[OrderBook]) =
    (book: OrderBook) =>
      if (book.orders.size > bookBatchSize)
        // Need to force the stream because of the book.remove operation
        (book
          .stream.take(bookBatchSize).force
          .foldLeft(new OrderBook(book.tickSize))(_ open _remove(book, _)),
          Some(book))
      else (book, None)

  // This has to be a def!
  // TODO: ^ Is that still true?
  implicit def orderBookFmt: DeltaFmtJson[OrderBook] =
    DeltaFmt.updateEventFmtJsonWithFold[OrderBook, Delta]("book",
      foldOrderBook, unfoldOrderBook)

  val askOrdering: Ordering[Double] = Ordering[Double]
  val bidOrdering: Ordering[Double] = askOrdering.reverse

  type FIFOQueue = Object2ObjectLinkedOpenHashMap[String, Order]

  class OrderBookSide(val index: Double2ObjectRBTreeMap[FIFOQueue],
                      val tickSize: Double,
                      val side: QuoteSide) extends Matching {

    val tickScale: Int = NumberUtils.scale(tickSize)
    def round(price: Double): Double = NumberUtils.round(price, tickScale)

    def isEmpty: Boolean = index.isEmpty
    def nonEmpty: Boolean = !index.isEmpty

    def bestPrice: Double = index.firstDoubleKey()
    def bestQty: Double = index.values.iterator.next.values.iterator.next.amount

    def peek: Order = {
      val p = index.firstDoubleKey()
      val q = index.get(p)
      q.get(q.firstKey())
    }

    def pop(): Order = {
      val p = index.firstDoubleKey()
      val q = index.get(p)
      val id = q.firstKey()

      // Get the order
      val order = q.get(id)

      // Remove order from queue, and queue from data structure, if now empty.
      q.remove(order)
      if (q.isEmpty) {
        index.remove(p)
      }

      // Return order
      order
    }

    def priceIterator: Iterator[java.lang.Double] = index.keySet().iterator().asScala

    // Does not support qtys that are larger than the next order.
    private def removeQtyFromTopOrder(approxQty: Double): Unit = {
      val order = peek
      val qty = NumberUtils.round8(approxQty)
      assert(order.amount >= qty)

      if (order.amount == qty) {
        pop()
      }
    }

    override def matchMutable(quoteSide: QuoteSide,
                              approxPriceLimit: Double,
                              approxSize: Double): Double = {
      assert(quoteSide == side)

      val size = NumberUtils.round8(approxSize)
      val priceLimit = round(approxPriceLimit)
      var remainder = size
      var i = 0
      while (nonEmpty && remainder > 0 && side.isBetterOrEq(bestPrice, priceLimit)) {
        val matchQty = math.min(remainder, bestQty)
        remainder = NumberUtils.round8(remainder - matchQty)
        matchPrices(i) = bestPrice
        matchQtys(i) = matchQty
        removeQtyFromTopOrder(matchQty)
        i += 1
      }
      matchPrices(i) = -1
      matchQtys(i) = -1
      remainder
    }

    def qtyAtPrice(d: Double): Double = {
      var sum = 0.0
      for (o <- index.get(d).values.iterator.asScala) {
        sum += o.amount
      }
      NumberUtils.round8(sum)
    }

    override def matchSilent(quoteSide: QuoteSide,
                             approxPriceLimit: Double,
                             approxSize: Double): Double = {
      assert(quoteSide == side)

      val size = NumberUtils.round8(approxSize)
      val priceLimit = round(approxPriceLimit)
      var remainder = size
      var i = 0

      val ordersIt = index.values.iterator.asScala.flatMap(_.values.iterator.asScala)
      var price = side.best
      while (ordersIt.hasNext && remainder > 0 && side.isBetterOrEq(price, priceLimit)) {
        val order = ordersIt.next()
        price = order.price.get
        val matchQty = math.min(remainder, order.amount)
        remainder = NumberUtils.round8(remainder - matchQty)
        matchPrices(i) = price
        matchQtys(i) = matchQty
        i += 1
      }
      matchPrices(i) = -1
      matchQtys(i) = -1
      remainder
    }

    override def matchSilentAvg(quoteSide: QuoteSide,
                                approxPriceLimit: Double,
                                approxSize: Double): (Double, Double) = {
      assert(quoteSide == side)

      val size = NumberUtils.round8(approxSize)
      val priceLimit = round(approxPriceLimit)
      var totalMatched = 0d
      var unroundedAvgPrice: Double = java.lang.Double.NaN
      var break = false
      val pricesIt = index.keySet.iterator.asScala

      while (!break && pricesIt.hasNext) {
        val price = pricesIt.next()
        val remainder = NumberUtils.round8(size - totalMatched)
        if (side.isBetterOrEq(price, priceLimit) && remainder > 0) {
          val qty = qtyAtPrice(price)
          val matchQty = math.min(remainder, qty)
          unroundedAvgPrice =
            if (java.lang.Double.isNaN(unroundedAvgPrice)) price
            else (unroundedAvgPrice * totalMatched + price * matchQty) / (totalMatched + matchQty)
          totalMatched = NumberUtils.round8(totalMatched + matchQty)
        } else break = true
      }
      (totalMatched, unroundedAvgPrice)
    }
  }

  object OrderBookSide {
    def newAsks(tickSize: Double): OrderBookSide = new OrderBookSide(
      new Double2ObjectRBTreeMap(DoubleComparators.NATURAL_COMPARATOR),
      tickSize, Ask)

    def newBids(tickSize: Double): OrderBookSide = new OrderBookSide(
      new Double2ObjectRBTreeMap(DoubleComparators.OPPOSITE_COMPARATOR),
      tickSize, Bid)

    class FifoIterator(queue: FIFOQueue) extends Iterator[Order] {
      override def size: Int = queue.size()
      private def it = queue.object2ObjectEntrySet().fastIterator().asScala.map(_.getValue)
      override def hasNext: Boolean = it.hasNext
      override def next(): Order = it.next()
    }

    object FifoIterator {
      val empty: FifoIterator = new FifoIterator(new Object2ObjectLinkedOpenHashMap())
    }
  }

}
