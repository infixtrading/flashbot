package com.infixtrading.flashbot.core

case class Candle(micros: Long,
                  open: Double,
                  high: Double,
                  low: Double,
                  close: Double,
                  volume: Option[Double] = None) extends Timestamped {
  def add(value: Double, newVolume: Option[Double] = None): Candle = copy(
    high = math.max(high, value),
    low = math.min(low, value),
    close = value,
    volume = newVolume.orElse(volume)
  )
}

object Candle {
  import io.circe._
  import io.circe.generic.semiauto._

  implicit val candleEn: Encoder[Candle] = deriveEncoder
  implicit val candleDe: Decoder[Candle] = deriveDecoder

//  case class CandleMD(source: String, topic: String, data: Candle)
//    extends GenMD[Candle] with Priced with HasProduct {
//
//    override def micros: Long = data.micros
//    override def dataType: String = "candles"
//    override def exchange: String = source
//    override def product: String = topic
//    override def price: Double = data.close
//  }
}
