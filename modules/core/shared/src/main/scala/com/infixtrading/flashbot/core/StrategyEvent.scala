package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.Exchange.ExchangeError

sealed trait StrategyEvent

/**
  * [[OrderTargetEvent]] represents any state changes to orders from the perspective of
  * the exchange. This usually includes all supported messages from the exchange's
  * "user data" feed.
  *
  * @param targetId the TargetId associated with the order event. This value is `None`
  *                 if the event is for an order that is not associated with the current
  *                 trading session. (I.e. the event occurred from outside the system,
  *                 either manually through the exchange interface, or by another strategy).
  * @param event the order event
  */
case class OrderTargetEvent(targetId: Option[TargetId], event: OrderEvent) extends StrategyEvent

/**
  * [[ExchangeErrorEvent]] represents an error emitted by the exchange. The error may be
  * anything from an order being rejected, rate limiting errors, or timeouts/internal errors
  * due to the exchange itself going down.
  *
  * Check out [[ExchangeError]] to see possible error responses.
  *
  * @param error the error emitted by the exchange
  */
case class ExchangeErrorEvent(error: ExchangeError) extends StrategyEvent

