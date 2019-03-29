package flashbot.core

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

case class Trade(id: String, micros: Long, price: Double, size: Double, direction: TickDirection)
  extends Timestamped with Priced

object Trade {
  implicit val tradeEn: Encoder[Trade] = deriveEncoder
  implicit val tradeDe: Decoder[Trade] = deriveDecoder
  implicit val tradeFmt: DeltaFmtJson[Trade] = DeltaFmt.defaultFmtJson[Trade]("trades")
}
