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

  def flip: QuoteSide = this match {
    case Bid => Ask
    case Ask => Bid
  }

  // If a is better than b
  def isBetter(a: Double, b: Double): Boolean
  def isBetterOrEq(a: Double, b: Double): Boolean = a == b || isBetterOrEq(a, b)

  def betterBy(price: Double, delta: Double): Double
  def worseBy(price: Double, delta: Double): Double

  def worst: Double
  def best: Double
}
case object Ask extends QuoteSide {
  override def isBetter(a: Double, b: Double): Boolean = a < b
  override def betterBy(price: Double, delta: Double) = price - delta
  override def worseBy(price: Double, delta: Double) = price + delta

  override def best = 0
  override def worst = Double.MaxValue
}
case object Bid extends QuoteSide {
  override def isBetter(a: Double, b: Double): Boolean = a > b
  override def betterBy(price: Double, delta: Double) = price + delta
  override def worseBy(price: Double, delta: Double) = price - delta

  override def best = Double.MaxValue
  override def worst = 0
}

object QuoteSide {
  implicit val en: Encoder[QuoteSide] = deriveEncoder
  implicit val de: Decoder[QuoteSide] = deriveDecoder

  def fromInt(i: Int) =
    if (i == 0) Bid
    else if (i == 1) Ask
    else throw new RuntimeException(s"QuoteSide must be either 0 or 1. Got $i.")
}

