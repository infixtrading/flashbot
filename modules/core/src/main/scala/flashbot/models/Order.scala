package flashbot.models

import java.util.Objects

import flashbot.core.{Ask, Bid, QuoteSide}
import flashbot.models.Order.Side
import io.circe.generic.JsonCodec
import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure, Encoder, HCursor, Json}

import scala.language.implicitConversions

object Order {
  sealed trait Side {
    def toQuote: QuoteSide = if (this == Buy) Bid else Ask
    def flip: Side = if (this == Buy) Sell else Buy
  }

  case object Buy extends Side {
    override def toString: String = "buy"
  }

  case object Sell extends Side {
    override def toString: String = "sell"
  }

  def apply(id: String, side: Side, amount: Double, price: Option[Double] = None): Order =
    new Order(id, side, amount, price)

  def unapply(order: Order): Option[(String, Side, Double, Option[Double])] =
    Some((order.id, order.side, order.amount, order.price))

  object Side {
    implicit def apply(str: String): Side = str match {
      case "sell" => Sell
      case "buy" => Buy
    }

    def fromSize(size: Double): Unit = {
      assert(size != 0)
      if (size > 0) Buy
      else Sell
    }

    implicit val sideEn: Encoder[Side] = Encoder.instance(_.toString.asJson)
    implicit val sideDe: Decoder[Side] = Decoder.decodeString.map(Side(_))
  }

  sealed trait TickDirection {
    override def toString: String = this match {
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
  case object MarketOrderType extends OrderType
  case object LimitOrderType extends OrderType

  object OrderType {
    def parseOrderType(str: String): OrderType = str match {
      case "market" => MarketOrderType
      case "limit" => LimitOrderType
    }
  }

  implicit val orderEncoder: Encoder[Order] = new Encoder[Order] {
    override def apply(o: Order): Json = Json.obj(
      "id" -> Json.fromString(o.id),
      "side" -> o.side.asJson,
      "amount" -> o.amount.asJson,
      "price" -> o.price.asJson
    )
  }

  implicit val orderDecoder: Decoder[Order] = new Decoder[Order] {
    override def apply(c: HCursor): Either[DecodingFailure, Order] = for {
      id <- c.downField("id").as[String]
      side <- c.downField("side").as[Side]
      amount <- c.downField("amount").as[Double]
      price <- c.downField("price").as[Option[Double]]
    } yield new Order(id, side, amount, price)
  }
}

class Order(val id: String,
            val side: Side,
            var amount: Double,
            val price: Option[Double]) {
  def setAmount(newAmount: Double): Unit =
    amount = newAmount

  override def equals(obj: Any): Boolean = obj match {
    case Order(_id, _side, _amount, _price) =>
      id == _id && side == _side && amount == _amount && price == _price
    case _ => false
  }

  override def hashCode(): Int = Objects.hash(id, side, Double.box(amount), price)
}

