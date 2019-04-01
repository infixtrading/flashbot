package flashbot.core

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

case class BalancePoint(balance: Double, micros: Long) extends Timestamped
object BalancePoint {
  implicit val en: Encoder[BalancePoint] = deriveEncoder
  implicit val de: Decoder[BalancePoint] = deriveDecoder
}
