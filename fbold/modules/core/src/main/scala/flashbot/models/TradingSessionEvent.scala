package flashbot.models

import flashbot.core.Tick
import flashbot.models.Order.{Liquidity, Side}

trait TradingSessionEvent

case class Fill(orderId: String,
                tradeId: Option[String],
                fee: Double,
                instrument: String,
                price: Double,
                size: Double,
                micros: Long,
                liquidity: Liquidity,
                side: Side) extends Tick


//case class LogMessage(message: String) extends TradingSessionEvent
//case class OrderTarget(market: Market,
//                       key: String,
//                       size: FixedSize,
//                       price: Option[BigDecimal],
//                       once: Option[Boolean] = None,
//                       postOnly: Option[Boolean] = None) extends TradingSessionEvent {
//  def id: TargetId = TargetId(market, key)
//}

