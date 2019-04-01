package flashbot.core

import flashbot.models.Order.TickDirection
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class Trade(id: String,
                 micros: Long,
                 price: Double,
                 size: Double,
                 direction: TickDirection)
  extends Timestamped with Priced

object Trade {
  implicit val tradeEn: Encoder[Trade] = deriveEncoder
  implicit val tradeDe: Decoder[Trade] = deriveDecoder
  implicit val tradeFmt: DeltaFmtJson[Trade] = DeltaFmt.defaultFmtJson[Trade]("trades")
}
