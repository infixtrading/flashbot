package flashbot.models.core

import flashbot.core.Instrument
import flashbot.models.core.Order.Side

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
                              size: Double) extends OrderRequest

