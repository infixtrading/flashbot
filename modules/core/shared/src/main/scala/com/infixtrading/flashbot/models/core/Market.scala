package com.infixtrading.flashbot.models.core

import io.circe._
import io.circe.generic.semiauto._

case class Market(exchange: String, symbol: String) {
  override def toString = s"$exchange/$symbol"
}
object Market {

  implicit def apply(str: String): Market = parse(str)

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

  implicit def en: Encoder[Market] = deriveEncoder[Market]
  implicit def de: Decoder[Market] = deriveDecoder[Market]

  implicit val marketKeyEncoder: KeyEncoder[Market] = KeyEncoder.instance(_.toString)
  implicit val marketKeyDecoder: KeyDecoder[Market] = KeyDecoder.instance(x => Some(parse(x)))

  implicit def pairToMarket(pair: (String, String)): Market = Market(pair._1, pair._2)
}

