package flashbot.server

import java.util

import flashbot.core._
import flashbot.models.Order.{Buy, Down, LimitOrderType, MarketOrderType, Sell, Side, Taker, TickDirection, Up}
import flashbot.models.OrderBook.OrderBookSide.FifoIterator
import flashbot.models.OrderBook.{FIFOQueue, OrderBookSide}
import flashbot.models.OrderCommand.PostOrderCommand
import flashbot.models._
import flashbot.util.NumberUtils
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.{Object2DoubleOpenHashMap, Reference2DoubleOpenHashMap}

import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import Simulator._
import cats.data

/**
  * The simulator is an exchange used for backtesting and paper trading. It takes an instance of a
  * real exchange as a parameter to use as a base implementation, but it simulates all API
  * interactions so that no network requests are actually made.
  */
class Simulator(base: Exchange, ctx: TradingSession, latencyMicros: Long = 0) extends Exchange {

  private var myOrders = new java.util.HashMap[String, OrderBook]
  private var depths = new java.util.HashMap[String, Ladder]
  private var prices = debox.Map.empty[String, Double]

  override protected[flashbot] def marketDataUpdate(md: MarketData[_]): Unit = {

    // Update latest depth/pricing data.
    md.data match {
      case ladder: Ladder =>
        // Clone the ladder coming in as market data to a local copy. This local copy
        // will be used as a temp ladder for simulating matches until the next market
        // data ladder comes.
        val existingDepths = depths.get(md.path.topic)
        if (existingDepths != null)
          ladder.copyInto(existingDepths)
        else
          depths.put(md.path.topic, ladder.copy())

        // For each price where we have orders, adjust the padding so that the order
        // totals match the ladder depth at that price. If the new ladder depth is
        // greater than the existing order totals (plus padding), add padding at the
        // end of the queue. If the new ladder depth is less than our existing totals
        // then that is interpreted as order cancellations. Remove proportional amount
        // from all padding.
        val book = myOrders.get(md.path.topic)
        for (price <- book.asks.priceIterator) {
          reconcileDepths(book, ladder, price, Sell)
        }
        for (price <- book.bids.priceIterator) {
          reconcileDepths(book, ladder, price, Buy)
        }

      case p: Priced =>
        prices(md.path.topic) = p.price

      case _ =>
    }

    // Match the new market data against our open orders to see if any of them
    // would have been filled.
    md.data match {
      /**
        * Determine fills that must have occurred on our resting orders based on the current
        * state of the ladder. E.g. our order book says we have a resting ask at 205, but
        * the ladder that arrived as market data says the best bid is 210, then our order
        * must have been filled.
        */
      case ladder: Ladder =>
        val book = myOrders.get(md.path.topic)
        while (intersectSideOnce(Ask, ladder, book, md.path.topic, md.micros) > 0) { }
        while (intersectSideOnce(Bid, ladder, book, md.path.topic, md.micros) > 0) { }

      /**
        * When a trade event comes in, match that amount against our order book. Matching
        * happens in one of two modes: swipe-through and depth-matching. Swipe-through means
        * our order gets filled only if the trade price is strictly worse than our order
        * price. This is a pessimistic matching mode used when there is no depth data
        * available. Depth-matching uses depth data to estimate where we are in the queue,
        * and therefore doesn't need to be pessimistic.
        */
      case trade: Trade =>
        val book = myOrders.get(md.path.topic)
        val isDepthMatching = depths.containsKey(md.path.topic)
        if (isDepthMatching) {
        } else {
          val quoteSide = if (trade.direction == Up) Ask else Bid
          val bookSide = book.sideOf(quoteSide)
          while (quoteSide.isBetter(bookSide.bestPrice, trade.price)) {
            // Match all orders at the best price in our book.
            bookSide.matchMutable(quoteSide, bookSide.bestPrice, Double.MaxValue)
            var i = -1
            bookSide.foreachMatch { (p, q) =>
              i += 1
              val matchedOrderId = bookSide.matchOrderIds.get(i)
              tradeCount += 1
              ctx.emit(OrderMatch(String.valueOf(tradeCount), md.path.topic, md.micros,
                q, p, trade.direction, matchedOrderId, s"__anon_$tradeCount"))
              ctx.emit(OrderDone(matchedOrderId, md.path.topic, quoteSide.toSide, Filled,
                Some(p), Some(0d)))
            }
          }
        }

      /**
        * When candle data comes in, we check if the high has crossed over any of our ask
        * orders or if the low has crossed any of our bids. If so, fill those orders.
        */
      case candle: Candle =>

      case _ =>
    }
  }

  override def order(req: PostOrderRequest): Future[ExchangeResponse] = {
    simulateSendRequest(req)
  }

  override def cancel(id: String, pair: Instrument): Future[ExchangeResponse] = {
    simulateSendRequest(CancelOrderRequest(id, pair))
  }

  var reqCounter: Long = -1
  val rspPromises = new Long2ObjectOpenHashMap[Promise[ExchangeResponse]]()

  private def simulateSendRequest(req: ExchangeRequest): Future[ExchangeResponse] = {
    reqCounter += 1
    val promise = Promise[ExchangeResponse]()
    rspPromises.put(reqCounter, promise)
    ctx.setTimeout(latencyMicros, SimulatedRequest(reqCounter, this, req))
    promise.future
  }

  private def reconcileDepths(book: OrderBook, ladder: Ladder,
                              price: Double, side: Side): Unit = {
    var paddingOrders: List[Order] = Nil
    var bookPadding = 0d
    var bookTotal = 0d
    val ordersAtPrice = book.ordersAtPrice(price)
    for (order <- new FifoIterator(ordersAtPrice)) {
      bookTotal += order.amount
      if (orderIsPadding(order)) {
        bookPadding += order.amount
        paddingOrders = order :: paddingOrders
      }
    }
    bookTotal = NumberUtils.round8(bookTotal)

    val ladderQty = ladder.qtyAtPrice(price)
    if (ladderQty > bookTotal) {
      // Increase padding at end
      addPadding(book, ordersAtPrice, price, ladderQty - bookTotal, side)
    } else if (ladderQty < bookTotal) {
      // Scale down padding uniformly
      val actualQty = bookTotal - bookPadding
      val factor = (ladderQty - actualQty) / bookPadding
      paddingOrders.foreach { order =>
        order.setAmount(NumberUtils.round8(order.amount * factor))
      }
    }
  }

  /**
    * Opens an order in the book with padding orders. A padding order is an order that
    * represents all other orders which aren't ours. They are used to maintain an estimated
    * queue position for our orders in order to simulate fills more accurately.
    */
  private def openOrderWithPadding(orders: FIFOQueue, clientId: String, product: String,
                                   side: Side, price: Double, size: Double): Unit = {
    val book = myOrders.get(product)
    val orderQueue = if (orders == null) book.ordersAtPrice(price) else orders
    if (orderQueue != null) {
      book.open(clientId, price, size, side)
    } else {
      val existingQty = depths.get(product).ladderSideFor(side.toQuote).qtyAtPrice(price)
      if (existingQty > 0)
        openPaddingOrder(book, price, existingQty, side)
      book.open(clientId, price, size, side)
    }
  }

  private def addPadding(book: OrderBook, orders: FIFOQueue, price: Double,
                         approxAmt: Double, side: Side): Unit = {
    val lastOrder = orders.get(orders.lastKey())
    if (orderIsPadding(lastOrder)) {
      lastOrder.setAmount(NumberUtils.round8(lastOrder.amount + approxAmt))
    } else {
      openPaddingOrder(book, price, NumberUtils.round8(approxAmt), side)
    }
  }

  var paddingCount: Int = -1
  private def openPaddingOrder(book: OrderBook, price: Double, size: Double, side: Side): Unit = {
    paddingCount += 1
    val oid = s"$PaddingPrefix$paddingCount"
    book.open(oid, price, size, side)
  }

  var tradeCount: Int = 0

  override protected[flashbot] def simulateReceiveRequest(micros: Long, sreq: SimulatedRequest): Unit = {
    val rsp: ExchangeResponse = sreq.request match {
      /**
        * Limit orders may be filled (fully or partially) immediately. If not immediately
        * fully filled, the remainder is placed on the resting order book.
        */
      case req @ LimitOrderRequest(clientOid, side, product, size, price, postOnly) =>
        val ladder = depths.get(product.symbol)
        val direction = if (side == Buy) Up else Down
        if (ladder != null) {
          // If `postOnly` is set, before we mutate the book, check if immediate
          // fills are possible. If so, that's a request error.
          if (postOnly && ladder.hasMatchingPrice(side, price)) {
            // Post only error
            RequestError(OrderRejectedError(req, PostOnlyConstraint))
          } else {
            // Emit received event
            ctx.emit(OrderReceived(clientOid, product, clientOid, LimitOrderType))

            // Match order against depths. Removes liquidity. Generates immediate fills.
            val remainder = ladder.matchMutable(side.flip.toQuote, price, size)
            ladder.foreachMatch { (matchPrice, matchSize) =>
              tradeCount += 1
              // The taker id is the order id of this limit order request.
              ctx.emit(OrderMatch(String.valueOf(tradeCount), product, micros, matchSize,
                matchPrice, direction, s"__anon_$tradeCount", clientOid))
            }

            // Put the remainder into our order book, or emit an OrderDone event.
            if (remainder == 0) {
              ctx.emit(OrderDone(clientOid, product, side, Filled, Some(price), Some(0)))
            } else {
              openOrderWithPadding(null, clientOid, product, side, price, remainder)
              ctx.emit(OrderOpen(clientOid, product, price, remainder, side))
            }

            RequestSuccess
          }

        } else {
          // If there is no ladder to match against, then approximate fills using the price.
          val marketPrice = prices.getOrElse(product.symbol, java.lang.Double.NaN)
          if (!java.lang.Double.isNaN(marketPrice)) {
            val isMatch = side.toQuote.isBetter(price, marketPrice)
            if (postOnly && isMatch) {
              // Post only error
              RequestError(OrderRejectedError(req, PostOnlyConstraint))

            } else if (isMatch) {
              // Generate complete immediate fill
              tradeCount += 1
              ctx.emit(OrderReceived(clientOid, product, clientOid, LimitOrderType))
              ctx.emit(OrderMatch(String.valueOf(tradeCount), product, micros, size, marketPrice,
                direction, s"__anon_$tradeCount", clientOid))
              ctx.emit(OrderDone(clientOid, product, side, Filled, Some(price), Some(0)))
              RequestSuccess

            } else {
              // Place order in book
              myOrders.get(product).open(clientOid, price, size, side)
              ctx.emit(OrderReceived(clientOid, product, clientOid, LimitOrderType))
              ctx.emit(OrderOpen(clientOid, product, price, size, side))
              RequestSuccess
            }
          } else {
            throw new RuntimeException(s"Not enough data to simulate limit orders for $product.")
          }
        }

      /**
        * Market orders need to be filled immediately.
        */
      case req @ MarketOrderRequest(clientOid, side, product, size) =>
        val ladder = depths.get(product.symbol)
        val direction = if (side == Buy) Up else Down
        if (ladder != null) {
          val excess = NumberUtils.round8(ladder.ladderSideForTaker(side).totalSize - size)
          if (excess < 0) {
            // Order size exceeded liquidity. Surface this as a fill or kill error
            RequestError(OrderRejectedError(req, FillOrKillConstraint))

          } else {
            // Perform the mutable match. Emit fills.
            val quoteSide = side.flip.toQuote
            assert(ladder.matchMutable(quoteSide, quoteSide.worst, size) == 0,
              "Non-zero remainder on market order")
            ctx.emit(OrderReceived(clientOid, product, clientOid, MarketOrderType))
            ladder.foreachMatch { (p, q) =>
              // Emit fill for each match
              tradeCount += 1
              ctx.emit(OrderMatch(String.valueOf(tradeCount), product, micros, q, p, direction,
                s"__anon_$tradeCount", clientOid))
            }
            ctx.emit(OrderDone(clientOid, product, side, Filled, None, None))
            RequestSuccess
          }

        } else {
          // If there is no ladder to match against, then approximate fills using the price.
          val marketPrice = prices.getOrElse(product.symbol, java.lang.Double.NaN)
          if (!java.lang.Double.isNaN(marketPrice)) {
            // Emit full fill at current market price
            ctx.emit(OrderReceived(clientOid, product, clientOid, MarketOrderType))
            tradeCount += 1
            ctx.emit(OrderMatch(String.valueOf(tradeCount), product, micros, size, marketPrice,
              direction, s"__anon_$tradeCount", clientOid))
            ctx.emit(OrderDone(clientOid, product, side, Filled, None, None))
            RequestSuccess

          } else {
            throw new RuntimeException(s"Not enough data to simulate market orders for $product.")
          }
        }

      /**
        * Removes the identified order from the resting order book.
        */
      case CancelOrderRequest(id, instrument) =>
        val book = myOrders.get(instrument.symbol)
        val order = book.orders.get(id)
        if (order != null) {
          book.done(id)
          ctx.emit(OrderDone(id, instrument.symbol, order.side, Canceled, order.price, Some(order.amount)))
          RequestSuccess
        } else {
          RequestError(BadRequest(s"Cannot cancel unknown order id: $id"))
        }
    }

    val prom = rspPromises.remove(sreq.reqId)
    prom.success(rsp)
  }

  override def baseAssetPrecision(pair: Instrument): Int = base.baseAssetPrecision(pair)

  override def quoteAssetPrecision(pair: Instrument): Int = base.quoteAssetPrecision(pair)

  override def lotSize(pair: Instrument): Option[Double] = base.lotSize(pair)

  override def fetchPortfolio = base.fetchPortfolio

  private def intersectSideOnce(quoteSide: QuoteSide,
                                takerDepths: Ladder,
                                restingOrders: OrderBook,
                                product: String,
                                micros: Long): Int = {
    val takerLadderSide = takerDepths.sideOf(quoteSide.flip)
    val direction = if (quoteSide == Ask) Up else Down
    restingOrders.matchMutable(quoteSide, takerLadderSide.bestPrice, takerLadderSide.bestQty)
    val orderIds = restingOrders.matchOrderIds.get
    var i = -1
    restingOrders.foreachMatch { (price, qty) =>
      i += 1
      val orderId = orderIds(i)
      if (!orderIdIsPadding(orderId)) {
        // If this is an actual fill, emit it.
        tradeCount += 1
        ctx.emit(OrderMatch(String.valueOf(tradeCount), product, micros, qty,
          price, direction, s"__anon_$tradeCount", orderId))
      }

      // Remove the liquidity from the ladder.
      takerDepths.matchMutable(quoteSide.flip, price, qty)
      assert(takerDepths.matchTotalQty == qty)
    }
    restingOrders.matchCount
  }

}

object Simulator {
  private val PaddingPrefix = "__padding_"

  private def orderIsPadding(order: Order): Boolean =
    order.id.startsWith(PaddingPrefix)

  private def orderIdIsPadding(id: String): Boolean = id.startsWith(PaddingPrefix)

}
