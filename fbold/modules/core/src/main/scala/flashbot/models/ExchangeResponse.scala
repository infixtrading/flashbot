package flashbot.models

sealed trait ExchangeResponse
case object RequestSuccess extends ExchangeResponse
case class RequestError(cause: ExchangeError) extends ExchangeResponse

trait RejectedReason {
  def message: String
}

case object PostOnlyConstraint extends RejectedReason {
  override def message = "Post-only order constraint not satisfied"
}

case object InsufficientFunds extends RejectedReason {
  override def message = "Insufficient funds for order"
}

case object FillOrKillConstraint extends RejectedReason {
  override def message: String = "Fill-or-kill order constraint not satisfied"
}

/**
  * [[ExchangeError]] represents an error emitted by the exchange. The error may be
  * anything from an order being rejected, rate limiting errors, or timeouts/internal
  * errors due to the exchange itself going down.
  */
sealed trait ExchangeError extends Exception {
  def message: String
}

case class OrderRejectedError(request: PostOrderRequest, reason: RejectedReason) extends ExchangeError {
  override def message = reason.message
}

case class BadRequest(message: String) extends ExchangeError

case class ServerError(message: String) extends ExchangeError

case class InternalError(error: Throwable) extends ExchangeError {
  override def message = error.getMessage
}

