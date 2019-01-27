package flashbot.core

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

sealed trait QuoteSide
case object Bid extends QuoteSide
case object Ask extends QuoteSide

object QuoteSide {
  implicit val en: Encoder[QuoteSide] = deriveEncoder
  implicit val de: Decoder[QuoteSide] = deriveDecoder
}

