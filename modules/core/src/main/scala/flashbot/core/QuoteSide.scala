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
  def isBetterOrEq(a: Double, b: Double): Boolean = a == b || isBetter(a, b)

  def isWorse(a: Double, b: Double): Boolean = !isBetterOrEq(a, b)
  def isWorseOrEq(a: Double, b: Double): Boolean = !isBetter(a, b)

  def makeBetterBy(price: Double, delta: Double): Double
  def makeWorseBy(price: Double, delta: Double): Double

  def isBetterBy(price: Double, than: Double): Double
  def isWorseBy(price: Double, than: Double): Double

  def worst: Double
  def best: Double

//  def isBestFirst: Boolean
}
case object Ask extends QuoteSide {
  override def isBetter(a: Double, b: Double): Boolean = a < b

  override def isBetterBy(price: Double, than: Double): Double = than - price
  override def isWorseBy(price: Double, than: Double): Double = price - than

  override def makeBetterBy(price: Double, delta: Double) = price - delta
  override def makeWorseBy(price: Double, delta: Double) = price + delta

  override def best = 0
  override def worst = Double.MaxValue
}

case object Bid extends QuoteSide {
  override def isBetter(a: Double, b: Double): Boolean = a > b

  override def isBetterBy(price: Double, than: Double): Double = price - than
  override def isWorseBy(price: Double, than: Double): Double = than - price

  override def makeBetterBy(price: Double, delta: Double) = price + delta
  override def makeWorseBy(price: Double, delta: Double) = price - delta

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

