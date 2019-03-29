package flashbot.models.api
import flashbot.core.{FixedSize, TargetId, Timestamped}
import flashbot.models.core.Market
import flashbot.models.core.Order.{Liquidity, Side}

trait TradingSessionEvent extends Timestamped

case class Fill(orderId: String,
                tradeId: Option[String],
                fee: Double,
                instrument: String,
                price: Double,
                size: Double,
                micros: Long,
                liquidity: Liquidity,
                side: Side) extends TradingSessionEvent


//case class LogMessage(message: String) extends TradingSessionEvent
//case class OrderTarget(market: Market,
//                       key: String,
//                       size: FixedSize,
//                       price: Option[BigDecimal],
//                       once: Option[Boolean] = None,
//                       postOnly: Option[Boolean] = None) extends TradingSessionEvent {
//  def id: TargetId = TargetId(market, key)
//}

