package flashbot.core

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

sealed trait QuoteSide {
  override def toString = this match {
    case Bid => "bid"
    case Ask => "ask"
  }

  def toSide: Side = this match {
    case Bid => Buy
    case Ask => Sell
  }
}
case object Bid extends QuoteSide
case object Ask extends QuoteSide

object QuoteSide {
  implicit val en: Encoder[QuoteSide] = deriveEncoder
  implicit val de: Decoder[QuoteSide] = deriveDecoder
}

