package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.Action.{ActionQueue, CancelLimitOrder, PostLimitOrder, PostMarketOrder}
import com.infixtrading.flashbot.engine.IdManager
import com.infixtrading.flashbot.core.Convert._
import com.infixtrading.flashbot.models.api.OrderTarget
import com.infixtrading.flashbot.models.core.Action

import scala.collection.immutable.Queue

/**
  * We have one target manager per exchange. It handles order targets received from a strategy
  * and emits actions to be executed by the engine.
  *
  * @param targets a queue of order targets for the exchange
  * @param mountedTargets a map of target ids that represents the theoretical order state
  *                       that should exist on the exchange. The value is the create order
  *                       Action which is associated with this target. For market orders,
  *                       this represents an in-flight order. For limit orders, this
  *                       represents an in-flight OR resting limit order.
  * @param ids an ID manager for linking orders to target ids
  */
case class TargetManager(instuments: InstrumentIndex,
                         targets: Queue[OrderTarget] = Queue.empty,
                         mountedTargets: Map[TargetId, Action] = Map.empty,
                         ids: IdManager = IdManager()) {

  def submitTarget(target: OrderTarget): TargetManager =
    copy(targets = targets.enqueue(target))

  def enqueueActions(exchange: Exchange, currentActions: ActionQueue)
                    (implicit prices: PriceIndex,
                     instruments: InstrumentIndex): (TargetManager, ActionQueue) = {

//    /**
//      * Shared order size calculation for both limit and market orders. Returns either a valid
//      * non-zero order size or None.
//      */
//    def calculateFixedOrderSize(exchangeName: String, size: Size, pair: Pair,
//                                isMarketOrder: Boolean): Option[FixedSize] = {
//
//      val fixedSizeRaw: FixedSize = size match {
//        /**
//          * Start with looking for fixed sizes, which will be used as order size directly.
//          */
//        case fs: FixedSize => fs
//
//        case Ratio(ratio, extraBaseAssets, basePegs) =>
//          val baseAccount = Account(exchangeName, pair.base)
//          val quoteAccount = Account(exchangeName, pair.quote)
//
//          // Determine the portfolio of accounts that hold our base assets.
//          val explicitBaseAssets = extraBaseAssets + pair.base
//          val peggedBaseAssets = pegs.of(explicitBaseAssets)
//          val baseAssets = portfolio.filter {
//            case (Account(_, asset), _) =>
//              (explicitBaseAssets ++ (if (basePegs) peggedBaseAssets else Set.empty))
//                .contains(asset)
//          }
//
//          // First calculate our existing base position.
//          // This is simply the sum of all base asset balances.
//          val pos = baseAssets.balances.values.sum
//
//          // Then calculate our position bounds. That is, what will our position be if we buy the
//          // maximum amount, and if we sell the maximum amount available for this order.
//          val buymax = ???
//          val sellmin = ???
//
//          // Now we can use the ratio to determine the target position and generate the order.
//
//
////          val hedge = hedges.getOrElse[Double](pair.base, 0)
//
//          // Build the max notional position value for each coin, based on its hedge
//          // Get the min of that collection. The notional value of the current coin
//          // divided by this minimum is the factor by which the ratio needs to be
//          // scaled down by.
//          val min = scopeCoins
//            .map(coin => -hedges.getOrElse[Double](coin, 0) * prices(Pair(coin, pair.quote)))
//            .filter(_ > 0)
//            .min
//          val weight = (-hedge * prices(pair)) / min
//          val weightedRatio = ratio / weight
//
//          val targetBase = -hedge - (weightedRatio * hedge)
//          val currentBase = balances.getOrElse[Double](pair.base, 0)
//          val lotSize = exchange.lotSize(pair)
//          val baseDiff =
//            if (lotSize.isDefined) (((targetBase - currentBase) / lotSize.get).toLong *
//              BigDecimal(lotSize.get)).doubleValue
//            else targetBase - currentBase
//
//          if (baseDiff > 0 && exchange.useFundsForMarketBuys && isMarketOrder) {
//            Funds(baseDiff * prices(pair))
//          } else {
//            Amount(baseDiff)
//          }
//      }
//
//      val fixedSizeRounded = fixedSizeRaw match {
//        case Amount(s) => Amount(roundBase(pair)(s))
//        case Funds(s) => Funds(roundQuote(pair)(s))
//      }
//
//      if (fixedSizeRounded.size == 0) None
//      else Some(fixedSizeRounded)
//    }

    if (currentActions.nonEmpty) {
      // If the current actions queue still has actions to work with, then don't do anything.
      (this, currentActions)

    } else if (targets.isEmpty) {
      // If both the actions and the targets queues are empty, there is again nothing to do.
      (this, currentActions)

    } else {
      // Otherwise if the current actions queue is empty, then populate it with the result of
      // expanding the next order target.
      targets.dequeue match {
        case (target, newTargetQueue) =>
          val actions = target match {

            /**
              * Market order target.
              */
            case ot @ OrderTarget(market, key, size, None, _, _) =>
              val instrument = instuments(market.exchange, market.symbol)
              List(PostMarketOrder(
                exchange.genOrderId,
                TargetId(instrument, key),
                size.side,
                Some(exchange.round(instrument)(size.as(instrument.security.get).get).amount),
                None
              ))

            /**
              * Limit order target.
              *
              * Emit a PostLimitOrder action. Unlike market orders, this action needs to be
              * idempotent. It also needs to clean up after any existing limit orders with
              * the same target id.
              */
            case ot @ OrderTarget(market, key, size, Some(price), Some(once), Some(postOnly)) =>
              val instrument = instuments(market.exchange, market.symbol)
              val targetId = TargetId(instrument, key)
              val post = PostLimitOrder(
                exchange.genOrderId,
                targetId,
                size.side,
                exchange.round(instrument)(size.as(instrument.security.get).get).amount,
                price,
                postOnly
              )

              val cancel = CancelLimitOrder(targetId)

              mountedTargets.get(targetId) match {
                /**
                  * Existing mounted target is identical to this one. Ignore for idempotency.
                  */
                case Some(action: PostLimitOrder)
                  if action.price == price && action.size == size.amount => Nil

                /**
                  * Existing mounted target is different than this target. Cancel the previous
                  * order and create the new one.
                  */
                case Some(_: PostLimitOrder) => List(cancel, post)

                /**
                  * No existing mounted target. Simply post the limit order.
                  */
                case None => List(post)
              }
          }

          // Detect if this target was a no-op, and if there are more targets, recurse.
          // This prevents the target queue from backing up.
          if (newTargetQueue.nonEmpty && actions.isEmpty) {
            copy(targets = newTargetQueue).enqueueActions(exchange, currentActions)
          } else {
            (copy(targets = newTargetQueue), currentActions.enqueue(actions))
          }
      }
    }
  }

  def initCreateOrder(targetId: TargetId, clientId: String, action: Action): TargetManager = {
    if (mountedTargets contains targetId) {
      throw new RuntimeException(s"Order target id $targetId already exists.")
    }

    copy(
      ids = ids.initCreateOrderId(targetId, clientId),
      mountedTargets = mountedTargets + (targetId -> action)
    )
  }

  def initCancelOrder(targetId: TargetId): TargetManager = {
    if (!mountedTargets.contains(targetId)) {
      throw new RuntimeException(s"Cannot cancel unmounted target id: $targetId")
    }

    copy(mountedTargets = mountedTargets - targetId)
  }

  def receivedOrder(clientId: String, actualId: String): TargetManager = {
    copy(ids = ids.receivedOrderId(clientId, actualId))
  }

  def openOrder(event: OrderOpen): TargetManager = {
    copy()
  }

  def orderComplete(actualId: String): TargetManager = {
    copy(
      mountedTargets = mountedTargets - ids.actualToTarget(actualId),
      ids = ids.orderIdComplete(actualId)
    )
  }
}

