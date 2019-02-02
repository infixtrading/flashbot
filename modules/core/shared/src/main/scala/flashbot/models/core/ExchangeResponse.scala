package flashbot.models.core

sealed trait ExchangeResponse
case object RequestOk extends ExchangeResponse
case class RequestFailed(cause: ExchangeError) extends ExchangeResponse

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

case class OrderRejected(request: OrderRequest, reason: RejectedReason) extends ExchangeError {
  override def message = reason.message
}

case class BadRequest(message: String) extends ExchangeError

case class ServerError(message: String) extends ExchangeError

case class InternalError(error: Throwable) extends ExchangeError {
  override def message = error.getMessage
}

