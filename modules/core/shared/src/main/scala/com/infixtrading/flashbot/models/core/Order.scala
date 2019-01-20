package com.infixtrading.flashbot.models.core
import com.infixtrading.flashbot.models.core.Order.Side
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.syntax._

object Order {
  sealed trait Side
  case object Buy extends Side {
    override def toString: String = "buy"
  }
  case object Sell extends Side {
    override def toString: String = "sell"
  }

  object Side {
    implicit def apply(str: String): Side = str match {
      case "sell" => Sell
      case "buy" => Buy
    }
  }

  sealed trait TickDirection {
    override def toString = this match {
      case Up => "up"
      case Down => "down"
    }

    def makerSide: Side = this match {
      case Up => Sell
      case Down => Buy
    }

    def takerSide: Side = this match {
      case Up => Buy
      case Down => Sell
    }
  }
  case object Up extends TickDirection
  case object Down extends TickDirection

  object TickDirection {
    implicit def apply(str: String): TickDirection = str.toLowerCase match {
      case "up" => Up
      case "down" => Down
    }

    implicit val en: Encoder[TickDirection] = Encoder.encodeString.contramap(_.toString)
    implicit val de: Decoder[TickDirection] = Decoder.decodeString.map(TickDirection.apply)

    def ofMakerSide(side: Side): TickDirection = side match {
      case Buy => Down
      case Sell => Up
    }

    def ofTakerSide(side: Side): TickDirection = side match {
      case Buy => Up
      case Sell => Down
    }
  }

  trait Liquidity
  case object Maker extends Liquidity
  case object Taker extends Liquidity

  sealed trait OrderType
  case object Market extends OrderType
  case object Limit extends OrderType

  object OrderType {
    def parseOrderType(str: String): OrderType = str match {
      case "market" => Market
      case "limit" => Limit
    }
  }

  case class Fill(orderId: String,
                  tradeId: Option[String],
                  fee: Double,
                  instrument: String,
                  price: Double,
                  size: Double,
                  micros: Long,
                  liquidity: Liquidity,
                  side: Side)
}

case class Order(id: String,
                 side: Side,
                 amount: Double,
                 price: Option[Double])
