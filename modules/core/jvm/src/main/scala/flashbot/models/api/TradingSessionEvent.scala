package flashbot.models.api
import flashbot.core.TargetId
import flashbot.models.core.{FixedSize, Market}

trait TradingSessionEvent
case class LogMessage(message: String) extends TradingSessionEvent
case class OrderTarget(market: Market,
                       key: String,
                       size: FixedSize,
                       price: Option[Double],
                       once: Option[Boolean] = None,
                       postOnly: Option[Boolean] = None) extends TradingSessionEvent {
  def id: TargetId = TargetId(market, key)
}

