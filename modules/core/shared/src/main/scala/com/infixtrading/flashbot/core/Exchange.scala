package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.Order.Side

object Exchange {

  sealed trait ExchangeRequest

  sealed trait OrderRequest extends ExchangeRequest {
    val clientOid: String
    val side: Side
    val product: Instrument
  }

  case class LimitOrderRequest(clientOid: String,
                               side: Side,
                               product: Instrument,
                               size: Double,
                               price: Double,
                               postOnly: Boolean) extends OrderRequest

  case class MarketOrderRequest(clientOid: String,
                                side: Side,
                                product: Instrument,
                                size: Option[Double],
                                funds: Option[Double]) extends OrderRequest

  trait RejectedReason {
    def message: String
  }

  case object PostOnlyConstraint extends RejectedReason {
    override def message = "Post-only order constraint not satisfied"
  }

  case object InsufficientFunds extends RejectedReason {
    override def message = "Insufficient funds for order"
  }

  sealed trait ExchangeError {
    def message: String
  }

  case class OrderRejected(reason: RejectedReason) extends ExchangeError {
    override def message = reason.message
  }

  case class BadRequest(message: String) extends ExchangeError

  case class ServerError(message: String) extends ExchangeError

  case class InternalError(error: Throwable) extends ExchangeError {
    override def message = error.getMessage
  }

  sealed trait ExchangeResponse

  case object RequestOk extends ExchangeResponse

  case class RequestFailed(cause: ExchangeError) extends ExchangeResponse

}

