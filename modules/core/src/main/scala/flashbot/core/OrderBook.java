/**
  * A mutable order book.
  */
class OrderBook(// Index of id -> order.
                protected[flashbot] val orders: debox.Map[String, Order] = debox.Map.empty,

                // Price ascending price levels for asks, with a queue of orders at each level.
                // The LinkedHashMap allows us to maintain insertion order while preserving
                // constant time order cancellation.
                private val asks: OrderIndex = mutable.TreeMap.empty(askOrdering),

                // Same as the asks data structure, but the ordering of the price levels is reversed.
                private val bids: OrderIndex = mutable.TreeMap.empty(bidOrdering),

                protected var lastUpdate: Option[Delta] = None)

      extends HasUpdateEvent[OrderBook, Delta] {

  assert(asks.ordering eq askOrdering)
  assert(bids.ordering eq bidOrdering)
  assert(orders.isEmpty)
  assert(asks.isEmpty)
  assert(bids.isEmpty)
  assert(lastUpdate.isEmpty)

  def bidsIterator = bids.valuesIterator.flatMap(_.valuesIterator)
  def asksIterator = asks.valuesIterator.flatMap(_.valuesIterator)
  def iterator = bidsIterator ++ asksIterator
  def stream: Stream[Order] = iterator.toStream

  private var askCount = 0
  private var bidCount = 0
  def size = askCount + bidCount

  private val _emptyArray: Array[Order] = Array.empty[Order]

  private var _asksArray: Array[Order] = _
  private var _bidsArray: Array[Order] = _

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

  override def equals(obj: Any) = obj match {
    case book: OrderBook if size == book.size =>
      stream.zip(book.stream).forall {
        case (o1, o2) => o1 == o2
      }
    case _ => false
  }

//  override def hashCode() = {
//    val b = new mutable.StringBuilder()
//    orders.foreachKey(b.append)
//    b.mkString.hashCode
//  }

  def isInitialized: Boolean = orders.nonEmpty

  override protected def withLastUpdate(d: Delta): OrderBook = {
    this.lastUpdate = Some(d)
    this
  }

  override protected def step(event: Delta): OrderBook = event match {
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
    if (orders.contains(order.id))
      throw new RuntimeException(s"OrderBook already contains order ${order.id}")

    // Update the index first.
    orders += (order.id -> order)

    // Then depending on the side, ensure the correct queue exists.
    // Also update the relevant count.
    val queue = order.side match {
      case Sell =>
        askCount += 1
        _asksArray = null
        asks.getOrElseUpdate(order.price.get, mutable.LinkedHashMap.empty)
      case Buy =>
        bidCount += 1
        _bidsArray = null
        bids.getOrElseUpdate(order.price.get, mutable.LinkedHashMap.empty)
    }

    // And add the order.
    queue += (order.id -> order)
    this
  }

  def open(id: String, price: Double, size: Double, side: Side): OrderBook =
    open(Order(id, side, size, Some(price)))

  def done(id: String): OrderBook =
    // Look up the order by it's id and remove it.
    orders get id match {
      case Some(order) =>
        this._removeOrderId(id, order.side)

      case None => this // Ignore "done" messages for orders not in the book
    }

  def remove(order: Order): Order = {
    this.done(order.id)
    order
  }

  def change(id: String, newSize: Double): OrderBook = {
    // Must be a valid order
    orders(id).setAmount(newSize)
    this
  }

  private def _removeOrderId(id: String, side: Side): OrderBook = {
    val o = this.orders(id)
    this.orders.remove(id)

    val index = side match {
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
    val queue = index(price)
    queue -= id

    // If the queue is now empty, remove that price level completely.
    if (queue.isEmpty) {
      index -= price
    }

    this
  }

  def spread: Option[Double] =
    if (asks.nonEmpty && bids.nonEmpty)
      Some(asks.firstKey - bids.firstKey)
    else None

  def fill(side: Side, quantity: Double, limit: Option[Double] = None): (Array[Double], Array[Double]) = {
    val ladder = if (side == Buy) asks else bids

    if (ladder.nonEmpty) {
      val (bestPrice, orderQueue) = ladder.head
      val isMatch = limit.forall(lim => if (side == Buy) lim >= bestPrice else lim <= bestPrice)
      if (isMatch) {
        // If there is a match, generate the fill, and recurse.
        val topOrder = orderQueue.head._2
        val filledQuantity = quantity min topOrder.amount
        val thisFill = (topOrder.price.get, filledQuantity)

        // Update the order with the new amount.
        topOrder.setAmount(topOrder.amount - filledQuantity)

        // If the updated order is 0, remove it.
        if (topOrder.amount == `0`) {
          this.done(topOrder.id)
        }

        // If the remainder is over 0 and the top order was removed, recurse.
        val remainder = quantity - filledQuantity
        if (remainder > `0` && topOrder.amount == `0`) {
          return thisFill :: this.fill(side, remainder, limit)
        }

        // Otherwise, return only this fill.
        return List(thisFill)
      }
    }

    // Fallback to returning an empty list of fills
    Nil
  }

  /**
    * Infers the tick size of the order book by finding the minimum distance between prices.
    */
  def tickSize: Double = ???

  val random = new Random()
  def genID: String = {
    var id = random.nextLong().toString
    while (!orders.isDefinedAt(id)) {
      id = random.nextLong().toString
    }
    id
  }
}
