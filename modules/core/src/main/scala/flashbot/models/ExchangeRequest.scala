package flashbot.models

import flashbot.core.{Exchange, Instrument, Tick}
import flashbot.models.Order.Side

sealed trait ExchangeRequest

sealed trait PostOrderRequest extends ExchangeRequest {
  val clientOid: String
  val side: Side
  val product: Instrument
}

case class LimitOrderRequest(clientOid: String,
                             side: Side,
                             product: Instrument,
                             size: Double,
                             price: Double,
                             postOnly: Boolean) extends PostOrderRequest

case class MarketOrderRequest(clientOid: String,
                              side: Side,
                              product: Instrument,
                              size: Double) extends PostOrderRequest

case class CancelOrderRequest(id: String,
                              product: Instrument) extends ExchangeRequest

