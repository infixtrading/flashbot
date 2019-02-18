package flashbot.models.core

import flashbot.core.Labelled
import flashbot.core.Instrument.CurrencyPair
import io.circe._
import io.circe.generic.semiauto._

import scala.language.implicitConversions

case class Market(exchange: String, symbol: String) extends Labelled {
  override def toString = s"$exchange/$symbol"
  override def label = {
    val ex = exchange.capitalize
    val sym = CurrencyPair.parse(symbol).map(_.label).getOrElse(symbol)
    s"$ex: $sym"
  }
}

object Market {

  implicit def apply(str: String): Market = parse(str)
  implicit def asString(market: Market): String = market.toString

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

  implicit def en: Encoder[Market] = Encoder.encodeString.contramap(_.toString)
  implicit def de: Decoder[Market] = Decoder.decodeString.map(apply)

  implicit val marketKeyEncoder: KeyEncoder[Market] = KeyEncoder.instance(_.toString)
  implicit val marketKeyDecoder: KeyDecoder[Market] = KeyDecoder.instance(x => Some(parse(x)))

  implicit def pairToMarket(pair: (String, String)): Market = Market(pair._1, pair._2)
}

