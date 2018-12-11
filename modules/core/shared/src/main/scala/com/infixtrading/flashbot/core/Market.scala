package com.infixtrading.flashbot.core
import io.circe.{KeyDecoder, KeyEncoder}

case class Market(exchange: String, symbol: String) {
  override def toString = s"$exchange/$symbol"
}
object Market {
  def parse(market: String): Market = {
    val parts = market.split("/")
    Market(parts(0), parts(1))
  }

  def parseOpt(market: String): Option[Market] = {
    var ret: Option[Market] = None
    try {
      ret = Some(parse(market))
    }
    ret
  }

  implicit val marketKeyEncoder: KeyEncoder[Market] = new KeyEncoder[Market] {
    override def apply(key: Market) = key.toString
  }

  implicit val marketKeyDecoder: KeyDecoder[Market] = new KeyDecoder[Market] {
    override def apply(key: String) = Some(parse(key))
  }

  implicit def pairToMarket(pair: (String, String)): Market = Market(pair._1, pair._2)
}

