package flashbot.core

import flashbot.models.core.Order.{Buy, Sell, Side}
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

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

