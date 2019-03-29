package flashbot.models

import flashbot.core.{DataType, InstrumentIndex, Labelled}
import flashbot.core.Instrument.CurrencyPair
import io.circe._
import io.circe.generic.semiauto._

import scala.language.implicitConversions

case class Market(exchange: String, symbol: String) extends Labelled {
  override def toString = s"$exchange.$symbol"
  override def label = {
    val ex = exchange.capitalize
    val sym = CurrencyPair.parse(symbol).map(_.label).getOrElse(symbol)
    s"$ex: $sym"
  }

  def settlementAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).settledIn.get)

  def securityAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).security.get)

  def baseAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).base)

  def quoteAccount(implicit instruments: InstrumentIndex): Account =
    Account(exchange, instruments(this).quote)

  def path[T](dataType: DataType[T]): DataPath[T] =
    DataPath(exchange, symbol, dataType)
}

object Market {

  implicit def apply(str: String): Market = parse(str)
  implicit def asString(market: Market): String = market.toString

  def parse(market: String): Market = {
    var parts = market.split("/")
    if (parts.length < 2)
      parts = market.split("\\.")
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

