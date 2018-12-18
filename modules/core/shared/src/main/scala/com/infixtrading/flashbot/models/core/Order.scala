package com.infixtrading.flashbot.models.core
import com.infixtrading.flashbot.models.core.Order.Side

object Order {
  sealed trait Side
  case object Buy extends Side {
    override def toString: String = "buy"
  }
  case object Sell extends Side {
    override def toString: String = "sell"
  }

  object Side {
    def parseSide(str: String): Side = str match {
      case "sell" => Sell
      case "buy" => Buy
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
