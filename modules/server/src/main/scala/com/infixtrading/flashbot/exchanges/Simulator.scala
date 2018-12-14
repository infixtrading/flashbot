package com.infixtrading.flashbot.exchanges

import com.infixtrading.flashbot.models.core.Ladder.ladderFillOrder
import com.infixtrading.flashbot.models.core.Order._
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.engine.TradingSession
import com.infixtrading.flashbot.models.core.{Ladder, Order, OrderBook}

import scala.collection.immutable.Queue

/**
  * The simulator is an exchange used for backtesting and paper trading. It takes an instance of a
  * real exchange as a parameter to use as a base implementation, but it simulates all API
  * interactions so that no network requests are actually made.
  */
class Simulator(base: Exchange, latencyMicros: Long = 0) extends Exchange {

  private var currentTimeMicros: Long = 0

  sealed trait APIRequest {
    def requestTime: Long
  }
  case class OrderReq(requestTime: Long, req: OrderRequest) extends APIRequest
  case class CancelReq(requestTime: Long, id: String, pair: Instrument) extends APIRequest

  private var apiRequestQueue = Queue.empty[APIRequest]

  private var myOrders = Map.empty[String, OrderBook]

  private var depths = Map.empty[String, Ladder]
  private var prices = Map.empty[String, Double]

  override def makerFee: Double = base.makerFee
  override def takerFee: Double = base.takerFee

  override def collect(session: TradingSession,
                       data: Option[MarketData[_]]): (Seq[Order.Fill], Seq[OrderEvent]) = {
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
    data match {
      case Some(md: MarketData[OrderBook]) =>
        // TODO: Turn aggregate full order books into aggregate depths here
        ???
      case Some(md: MarketData[Ladder]) =>
        depths = depths + (md.topic -> md.data)

      case Some(md: MarketData[Priced]) =>
        prices = prices + (md.topic -> md.data.price)

      /**
        * Match trades against the aggregate book. This is a pretty naive matching strategy.
        * We should use a more precise matching engine for full order books. Also, this mutates
        * our book depths until they are overwritten by a more recent depth snapshot.
        */
      case Some(md: MarketData[Trade]) if depths.isDefinedAt(md.topic) =>
        // First simulate fills on the aggregate book. Remove the associated liquidity from
        // the depths.
        val simulatedFills =
          ladderFillOrder(depths(md.topic), md.data.side, Some(md.data.size), None)
        simulatedFills.foreach { case (fillPrice, fillAmount) =>
            depths = depths + (md.topic -> depths(md.topic).updateLevel(
              md.data.side match {
                case Buy => Ask
                case Sell => Bid
              }, fillPrice, depths(md.topic).quantityAtPrice(fillPrice).get - fillAmount
            ))
        }

        // Then use the fills to determine if any of our orders would have executed.
        if (myOrders.isDefinedAt(md.topic)) {
          val lastFillPrice = simulatedFills.last._1
          val filledOrders = md.data.side match {
            case Buy =>
              myOrders(md.topic).asks.filter(_._1 < lastFillPrice).values.toSet.flatten
            case Sell =>
              myOrders(md.topic).bids.filter(_._1 > lastFillPrice).values.toSet.flatten
          }
          filledOrders.foreach { order =>
            // Remove order from private book
            myOrders = myOrders + (md.topic -> myOrders(md.topic).done(order.id))

            // Emit OrderDone event
            events :+= OrderDone(order.id, md.topic, order.side, Filled, order.price, Some(0))

            // Emit the fill
            fills :+= Fill(order.id, Some(md.data.id), makerFee, md.topic, order.price.get,
              order.amount, md.micros, Maker, order.side)
          }
        }
      case _ =>
    }

    (fills, events)
  }

  override def order(req: OrderRequest): Unit = {
    apiRequestQueue = apiRequestQueue.enqueue(OrderReq(currentTimeMicros, req))
    tick()
  }

  override def cancel(id: String, pair: Instrument): Unit = {
    apiRequestQueue = apiRequestQueue.enqueue(CancelReq(currentTimeMicros, id, pair))
    tick()
  }

  override def baseAssetPrecision(pair: Instrument): Int = base.baseAssetPrecision(pair)

  override def quoteAssetPrecision(pair: Instrument): Int = base.quoteAssetPrecision(pair)

  override def useFundsForMarketBuys: Boolean = base.useFundsForMarketBuys

  override def lotSize(pair: Instrument): Option[Double] = base.lotSize(pair)

  override def fetchPortfolio = base.fetchPortfolio
}
