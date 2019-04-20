package flashbot.server

import flashbot.core._
import flashbot.models.Order.{Buy, Sell, Taker}
import flashbot.models.OrderCommand.PostOrderCommand
import flashbot.models._
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.{Object2DoubleOpenHashMap, Reference2DoubleOpenHashMap}

import scala.collection.immutable.Queue
import scala.collection.mutable
import scala.concurrent.{Future, Promise}

/**
  * The simulator is an exchange used for backtesting and paper trading. It takes an instance of a
  * real exchange as a parameter to use as a base implementation, but it simulates all API
  * interactions so that no network requests are actually made.
  */
class Simulator(base: Exchange, ctx: TradingSession, latencyMicros: Long = 0) extends Exchange {

//  private var syntheticCurrentMicros: Long = 0

//  sealed trait APIRequest {
//    def requestTime: Long
//  }
//  case class OrderReq(requestTime: Long, req: OrderRequest) extends APIRequest
//  case class CancelReq(requestTime: Long, id: String, pair: Instrument) extends APIRequest

//  private var apiRequestQueue = Queue.empty[APIRequest]

  private var myOrders = new java.util.HashMap[String, OrderBook]

  private var depths = new java.util.HashMap[String, Ladder]
  private var prices = new Object2DoubleOpenHashMap[String, Double]

  override def makerFee: Double = base.makerFee
  override def takerFee: Double = base.takerFee

  override def marketDataUpdate(md: MarketData[_]): Unit = {

    // Update latest depth/pricing data.
    md.data match {
      case ladder: Ladder =>
        // Clone the ladder coming in as market data to a local copy. This local copy
        // will be used as a temp ladder for simulating matches until the next market
        // data ladder comes.
        if (depths.containsKey(md.path.topic))
          ladder.copyInto(depths.get(md.path.topic))
        else
          depths.put(md.path.topic, ladder.clone())

      case p: Priced =>
        prices.put(md.path.topic, p.price)

      case _ =>
    }


    // Generate fills
    md.data match {
      case ladder: Ladder =>

      case trade: Trade =>

      case candle: Candle =>

      case _ =>
    }



    def processMatchedOrders(topic: String, micros: Long, orders: Iterable[Order]): Unit = {
      orders.foreach { order =>
        // Remove order from private book
        myOrders = myOrders + (topic -> myOrders(topic)._done(order.id))

        // Emit OrderDone event
        events :+= OrderDone(order.id, topic, order.side, Filled, order.price, Some(0))

        // Emit the fill
        fills :+= Fill(order.id, Some("t_" + order.id), makerFee, topic, order.price.get,
          order.amount, micros, Maker, order.side)
      }
    }

    // Generate fills
    data.map(_.data) match {
      /**
        * Match trades against the aggregate book. This is a pretty naive matching strategy.
        * We should use a more precise matching engine for full order books. Also, this mutates
        * our book depths until they are overwritten by a more recent depth snapshot.
        */
      case Some(trade: Trade) if depths.isDefinedAt(data.get.topic) =>
        val topic = data.get.topic
        // First simulate fills on the aggregate book. Remove the associated liquidity from
        // the depths.
        val simulatedFills = Ladder
          .ladderFillOrder(depths(topic), trade.direction.takerSide, Some(trade.size), None)
        simulatedFills.foreach { case (fillPrice, fillAmount) =>
          depths = depths + (topic -> depths(topic).updateLevel(
            trade.direction match {
              case Up => Ask
              case Down => Bid
            }, fillPrice, depths(topic).quantityAtPrice(fillPrice).get - fillAmount
          ))
        }

        // Then use the fills to determine if any of our orders would have executed.
        if (myOrders.isDefinedAt(topic)) {
          val lastFillPrice = simulatedFills.last._1
          val filledOrders = trade.direction match {
            case Up =>
              myOrders(topic).asks.index.filter(_._1 < lastFillPrice).values.toSet.flatten
            case Down =>
              myOrders(topic).bids.index.filter(_._1 > lastFillPrice).values.toSet.flatten
          }

          filledOrders.foreach { order =>
            // Remove order from private book
            myOrders = myOrders + (topic -> myOrders(topic)._done(order.id))

            // Emit OrderDone event
            events :+= OrderDone(order.id, topic, order.side, Filled, order.price, Some(0))

            // Emit the fill
            fills :+= Fill(order.id, Some(trade.id), makerFee, topic, order.price.get,
              order.amount, trade.micros, Maker, order.side)
          }
        }

      case Some(trade: Trade) if prices.isDefinedAt(data.get.topic) =>
        val topic = data.get.topic
        val price = prices(topic)
        if (myOrders.isDefinedAt(topic)) {
          val matchedAsks: Iterable[Order] =
            myOrders(topic).asks.index.takeWhile(_._1 < price).flatMap(_._2)
          val matchedBids: Iterable[Order] =
            myOrders(topic).bids.index.takeWhile(_._1 > price).flatMap(_._2)

          processMatchedOrders(topic, data.get.micros, matchedAsks ++ matchedBids)
        }

      /**
        * When candle data comes in, we check if the high has crossed over any of our ask
        * orders or if the low has crossed any of our bids. If so, fill those orders.
        */
      case Some(candle @ Candle(micros, open, high, low, close, volume)) =>
        val topic = data.get.topic
        if (myOrders.isDefinedAt(topic)) {
          val matchedAsks: Iterable[Order] =
            myOrders(topic).asks.index.takeWhile(_._1 < high).flatMap(_._2)
          val matchedBids: Iterable[Order] =
            myOrders(topic).bids.index.takeWhile(_._1 > low).flatMap(_._2)

          processMatchedOrders(topic, micros, matchedAsks ++ matchedBids)
        }

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

  override protected[flashbot] def simulateReceiveRequest(sreq: SimulatedRequest): Unit = {
    val rsp: ExchangeResponse = sreq.request match {
      /**
        * Limit orders may be filled (fully or partially) immediately. If not immediately
        * fully filled, the remainder is placed on the resting order book.
        */
      case req@LimitOrderRequest(clientOid, side, product, size, price, postOnly) =>
        val ladder = depths.get(product.symbol)
        if (ladder != null) {
          // If `postOnly` is set, before we mutate the book, check if immediate
          // fills are possible. If so, that's a request error.
          if (postOnly && ladder.hasMatchingPrice(side, price)) {
            // Post only error
          } else {
            // Match order against book. Removes liquidity. Generates immediate fills.

            // Put the remainder into our own order book.
          }
        } else {

          // If there is no ladder to match against, then approximate fills using the price.
          val marketPrice = prices.getOrDefault(product.symbol, java.lang.Double.NaN)
          if (!java.lang.Double.isNaN(marketPrice)) {
            val isMatch = side.flip.toQuote.isBetter(marketPrice, price)
            if (postOnly && isMatch) {
              // Post only error

            } else if (isMatch) {
              // Generate complete immediate fill

            } else {
              // Place order in book
            }
          } else {
            throw new RuntimeException(s"Not enough data to simulate limit orders for $product.")
          }
        }

      /**
        * Market orders need to be filled immediately.
        */
      case MarketOrderRequest(clientOid, side, product, size) =>
        val ladder = depths.get(product.symbol)
        if (ladder != null) {
          val total = ladder.ladderSideForTaker(side).totalSize
          val excess = ladder.round(total - size)
          if (excess < 0) {
            // Order size exceeded liquidity error
          } else {
            // Perform the mutable match. Emit fills.
            assert(ladder.matchMarket(side, size, silent = false) == 0,
              "Non-zero remainder on market order")
            ladder.foreachMatch { (p, q) =>
              // Emit fill
            }
          }

        } else {
          // If there is no ladder to match against, then approximate fills using the price.
          val marketPrice = prices.getOrDefault(product.symbol, java.lang.Double.NaN)
          if (!java.lang.Double.isNaN(marketPrice)) {
            // Emit full fill at current market price

          } else {
            throw new RuntimeException(s"Not enough data to simulate market orders for $product.")
          }
        }

      /**
        * Removes the identified order from the resting order book.
        */
      case CancelOrderRequest(id, instrument) =>
        val order = myOrders(instrument).orders(id)
        myOrders = myOrders + (pair.symbol -> myOrders(pair).done(id))
//        events = events :+ OrderDone(id, pair, order.side, Canceled,
//          order.price, Some(order.amount))

    }

    val prom = rspPromises.remove(sreq.reqId)
    prom.success(rsp)

//        if (postOnly && ladder != null) {
//          val remainder = ladder.ladderFillOrder(ladder, side, size, price, silent = true)
//        }

//        val immediateFills =
//          if (depths.containsKey(product))
//            Ladder.ladderFillOrder(depths.get(product), side, size, price)
////                      .map { case (fillPrice, fillQuantity) =>
////                        Fill(clientOid, Some(clientOid), takerFee, product, fillPrice, fillQuantity,
////                          evTime, Taker, side)
////                      }
//          else if (prices.containsKey(product)) {
//            side match {
//              case Buy if price > prices.get(product) =>
//                Seq(Fill(clientOid, Some(clientOid), takerFee, product, prices.get(product),
//                  size, evTime, Taker, side))
//              case Sell if price < prices.get(product) =>
//                Seq(Fill(clientOid, Some(clientOid), takerFee, product, prices.get(product),
//                  size, evTime, Taker, side))
//              case _ => Seq()
//            }
//          } else {
//            throw new RuntimeException(s"Not enough data to simulate limit orders for $product.")
//          }
//
//        if (immediateFills.nonEmpty && postOnly) {
//          errors :+= OrderRejectedError(req, PostOnlyConstraint)
//        } else {
//          fills ++= immediateFills
//
//          events :+= OrderReceived(clientOid, product, Some(clientOid), Order.LimitOrder)
//
//          // Either complete or open the limit order
//          val remainingSize = size - immediateFills.map(_.size).sum
//          if (remainingSize > 0) {
//            myOrders = myOrders + (product.symbol ->
//              myOrders.getOrElse(product, OrderBook())
//                ._open(clientOid, price, remainingSize, side))
//            events :+= OrderOpen(clientOid, product, price, remainingSize, side)
//          } else {
//            events :+= OrderDone(clientOid, product, side, Filled, Some(price), Some(0))
//          }
//        }
//
//      /**
//        * Market orders need to be filled immediately.
//        */
//      case MarketOrderRequest(clientOid, side, product, size, funds) =>
//        if (depths.isDefinedAt(product)) {
//          fills = fills ++
//            Ladder.ladderFillOrder(depths(product), side, size, funds.map(_ * (1 - takerFee)))
//              .map { case (price, quantity) => Fill(clientOid, Some(clientOid), takerFee,
//                product, price, quantity, evTime, Taker, side)}
//
//        } else if (prices.isDefinedAt(product)) {
//          // We may not have aggregate book data, in that case, simply use the last price.
//          fills = fills :+ Fill(
//            clientOid, Some(clientOid), takerFee, product, prices(product),
//            if (side == Buy) size.getOrElse(funds.get * (1 - takerFee) / prices(product))
//            else size.get,
//            evTime, Taker, side
//          )
//
//        } else {
//          throw new RuntimeException(s"No pricing $product data available for simulation")
//        }
//
//        events = events :+
//          OrderReceived(clientOid, product, Some(clientOid), Order.MarketOrder) :+
//          OrderDone(clientOid, product, side, Filled, None, None)
//    }
  }

  override def baseAssetPrecision(pair: Instrument): Int = base.baseAssetPrecision(pair)

  override def quoteAssetPrecision(pair: Instrument): Int = base.quoteAssetPrecision(pair)

  override def lotSize(pair: Instrument): Option[Double] = base.lotSize(pair)

  override def fetchPortfolio = base.fetchPortfolio
}
