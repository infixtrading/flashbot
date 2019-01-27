package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.engine.TradingSession
import com.infixtrading.flashbot.models.core.Ladder.ladderFillOrder
import com.infixtrading.flashbot.models.core.Order._
import com.infixtrading.flashbot.models.core._

import scala.collection.immutable.Queue
import scala.concurrent.Future

/**
  * The simulator is an exchange used for backtesting and paper trading. It takes an instance of a
  * real exchange as a parameter to use as a base implementation, but it simulates all API
  * interactions so that no network requests are actually made.
  */
class Simulator(base: Exchange, latencyMicros: Long = 0) extends Exchange {

  override implicit val ec = scala.concurrent.ExecutionContext.global

  private var currentTimeMicros: Long = 0

  sealed trait APIRequest {
    def requestTime: Long
  }
  case class OrderReq(requestTime: Long, req: OrderRequest) extends APIRequest
  case class CancelReq(requestTime: Long, id: String, pair: Instrument) extends APIRequest

  private var apiRequestQueue = Queue.empty[APIRequest]

  private var myOrders = Map.empty[String, OrderBook]

  private var books = Map.empty[String, OrderBook]
  private var depths = Map.empty[String, Ladder]
  private var prices = Map.empty[String, Double]

  override def makerFee: Double = base.makerFee
  override def takerFee: Double = base.takerFee

  override def collect(session: TradingSession,
                       data: Option[MarketData[_]]): (Seq[Order.Fill], Seq[OrderEvent], Seq[ExchangeError]) = {
    var fills = Seq.empty[Order.Fill]
    var events = Seq.empty[OrderEvent]

    // Update the current time, based on the time of the incoming market data.
    currentTimeMicros = math.max(data.map(_.micros).getOrElse(0L), currentTimeMicros)

    // Dequeue and process API requests that have passed the latency threshold
    while (apiRequestQueue.headOption
        .exists(_.requestTime + latencyMicros <= currentTimeMicros)) {
      apiRequestQueue.dequeue match {
        case (r: APIRequest, rest) =>
          val evTime = r.requestTime + latencyMicros
          r match {
            case OrderReq(requestTime, req) => req match {
              /**
                * Limit orders may be filled (fully or partially) immediately. If not immediately
                * fully filled, the remainder is placed on the resting order book.
                */
              case LimitOrderRequest(clientOid, side, product, size, price, postOnly) =>
                if (!depths.isDefinedAt(product)) {
                  throw new RuntimeException("Aggregate order books are required to simulate " +
                    "limit orders.")
                }

                val immediateFills =
                  ladderFillOrder(depths(product), side, Some(size), None, Some(price))
                  .map { case (fillPrice, fillQuantity) =>
                    Fill(clientOid, Some(clientOid), takerFee, product, fillPrice, fillQuantity,
                      evTime, Taker, side)
                  }
                fills ++= immediateFills

                events :+= OrderReceived(clientOid, product, Some(clientOid), Order.Limit)

                // Either complete or open the limit order
                val remainingSize = size - immediateFills.map(_.size).sum
                if (remainingSize > 0) {
                  myOrders = myOrders + (product.symbol ->
                    myOrders.getOrElse(product, OrderBook())
                      .open(clientOid, price, remainingSize, side))
                  events :+= OrderOpen(clientOid, product, price, remainingSize, side)
                } else {
                  events :+= OrderDone(clientOid, product, side, Filled, Some(price), Some(0))
                }

              /**
                * Market orders need to be filled immediately.
                */
              case MarketOrderRequest(clientOid, side, product, size, funds) =>
                if (depths.isDefinedAt(product)) {
                  fills = fills ++
                    ladderFillOrder(depths(product), side, size, funds.map(_ * (1 - takerFee)))
                      .map { case (price, quantity) => Fill(clientOid, Some(clientOid), takerFee,
                        product, price, quantity, evTime, Taker, side)}

                } else if (prices.isDefinedAt(product)) {
                  // We may not have aggregate book data, in that case, simply use the last price.
                  fills = fills :+ Fill(
                    clientOid, Some(clientOid), takerFee, product, prices(product),
                    if (side == Buy) size.getOrElse(funds.get * (1 - takerFee) / prices(product))
                    else size.get,
                    evTime, Taker, side
                  )

                } else {
                  throw new RuntimeException(s"No pricing $product data available for simulation")
                }

                events = events :+
                  OrderReceived(clientOid, product, Some(clientOid), Order.Market) :+
                  OrderDone(clientOid, product, side, Filled, None, None)
            }

            /**
              * Removes the identified order from the resting order book.
              */
            case CancelReq(_, id, pair) =>
              val order = myOrders(pair).orders(id)
              myOrders = myOrders + (pair.symbol -> myOrders(pair).done(id))
              events = events :+ OrderDone(id, pair, order.side, Canceled,
                order.price, Some(order.amount))
          }
          apiRequestQueue = rest
      }
    }

    // Update latest depth/pricing data.
    data.map(_.data) match {
      case Some(book: OrderBook) =>
        books = books + (data.get.topic -> book)

      case Some(ladder: Ladder) =>
        depths = depths + (data.get.topic -> ladder)

      case Some(p: Priced) =>
        prices = prices + (data.get.topic -> p.price)

      case _ =>
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
        val simulatedFills =
        ladderFillOrder(depths(topic), trade.direction.takerSide, Some(trade.size), None)
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
            myOrders = myOrders + (topic -> myOrders(topic).done(order.id))

            // Emit OrderDone event
            events :+= OrderDone(order.id, topic, order.side, Filled, order.price, Some(0))

            // Emit the fill
            fills :+= Fill(order.id, Some(trade.id), makerFee, topic, order.price.get,
              order.amount, trade.micros, Maker, order.side)
          }
        }

      /**
        * When candle data comes in, we check if the high has crossed over any of our ask
        * orders or if the low has crossed any of our bids. If so, fill those orders.
        */
      case Some(Candle(micros, open, high, low, close, volume)) =>
        val topic = data.get.topic
        if (myOrders.isDefinedAt(topic)) {
          val matchedAsks: Stream[Order] =
            myOrders(topic).asks.index.toStream.takeWhile(_._1 < high).flatMap(_._2)
          val matchedBids: Stream[Order] =
            myOrders(topic).bids.index.toStream.takeWhile(_._1 > low).flatMap(_._2)
          (matchedAsks ++ matchedBids).foreach { order =>
            // Remove order from private book
            myOrders = myOrders + (topic -> myOrders(topic).done(order.id))

            // Emit OrderDone event
            events :+= OrderDone(order.id, topic, order.side, Filled, order.price, Some(0))

            // Emit the fill
            fills :+= Fill(order.id, Some("t_" + order.id), makerFee, topic, order.price.get,
              order.amount, micros, Maker, order.side)
          }
        }

      case _ =>
    }

    (fills, events, Seq.empty)
  }

  override def order(req: OrderRequest): Future[ExchangeResponse] = {
    apiRequestQueue = apiRequestQueue.enqueue(OrderReq(currentTimeMicros, req))
    Future.successful(RequestOk)
  }

  override def cancel(id: String, pair: Instrument): Future[ExchangeResponse] = {
    apiRequestQueue = apiRequestQueue.enqueue(CancelReq(currentTimeMicros, id, pair))
    Future.successful(RequestOk)
  }

  override def baseAssetPrecision(pair: Instrument): Int = base.baseAssetPrecision(pair)

  override def quoteAssetPrecision(pair: Instrument): Int = base.quoteAssetPrecision(pair)

  override def lotSize(pair: Instrument): Option[Double] = base.lotSize(pair)

  override def fetchPortfolio = base.fetchPortfolio
}
