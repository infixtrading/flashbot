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
}
