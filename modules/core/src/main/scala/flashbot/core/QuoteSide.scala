package flashbot.core

import flashbot.models.Order.{Buy, Sell, Side}
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

  def toInt: Int = this match {
    case Bid => 0
    case Ask => 1
  }
}
case object Bid extends QuoteSide
case object Ask extends QuoteSide

object QuoteSide {
  implicit val en: Encoder[QuoteSide] = deriveEncoder
  implicit val de: Decoder[QuoteSide] = deriveDecoder

  def fromInt(i: Int) =
    if (i == 0) Bid
    else if (i == 1) Ask
    else throw new RuntimeException(s"QuoteSide must be either 0 or 1. Got $i.")
}

