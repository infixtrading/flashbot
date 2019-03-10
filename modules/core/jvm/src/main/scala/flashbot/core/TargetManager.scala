package flashbot.core

import TradingSession._
import flashbot.models.api.OrderTarget
import flashbot.models.core.Action.{ActionQueue, CancelLimitOrder, PostLimitOrder, PostMarketOrder}
import flashbot.models.core.{Action, OrderOpen}

import scala.collection.immutable.Queue

/**
  * We have one target manager per exchange. It handles order targets received from a strategy
  * and emits actions to be executed by the engine.
  *
  * @param instruments the instrument index.
  * @param targets a queue of order targets for the exchange.
  * @param mountedTargets a map of target ids that represents the theoretical order state
  *                       that should exist on the exchange. The value is the create order
  *                       Action which is associated with this target. For market orders,
  *                       this represents an in-flight order. For limit orders, this
  *                       represents an in-flight OR resting limit order.
  * @param ids an ID manager for linking orders to target ids.
  */
case class TargetManager(instruments: InstrumentIndex,
                         targets: Queue[OrderTarget] = Queue.empty,
                         mountedTargets: Map[TargetId, Action] = Map.empty,
                         ids: IdManager = IdManager()) {

  def submitTarget(target: OrderTarget): TargetManager =
    copy(targets = targets.enqueue(target))

  def enqueueActions(exchange: Exchange, currentActions: ActionQueue)
                    (implicit ctx: TradingSession,
                     metrics: Metrics): (TargetManager, ActionQueue) = {

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
          val rawActions = target match {

            /**
              * Market order target.
              */
            case ot @ OrderTarget(market, key, size, None, _, _) =>
              val instrument = instruments(market)
              List(PostMarketOrder(
                exchange.genOrderId,
                TargetId(market, key),
                size.side,
                Some(ctx.round(market, size.as(instrument.security.get)).amount),
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
              val instrument = instruments(market)
              val targetId = TargetId(market, key)
              val post = PostLimitOrder(
                exchange.genOrderId,
                targetId,
                size.side,
                ctx.round(market, size.as(instrument.security.get)).amount,
                price,
                postOnly
              )

              val cancel = CancelLimitOrder(targetId)

              mountedTargets.get(targetId) match {
                /**
                  * Existing mounted target is identical to this one. Ignore for idempotency.
                  */
                case Some(action: PostLimitOrder)
                  if action.price == price && action.size == size.amount
                    && action.side == size.side && action.postOnly == postOnly => Nil

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

          val actions = rawActions.filterNot(_.getSize.contains(0))

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

  def orderRejected(clientId: String): TargetManager = {
    copy(
      mountedTargets = mountedTargets - ids.clientToTarget(clientId),
      ids = ids.orderRejected(clientId)
    )
  }
}
