package flashbot.core

import debox.Buffer
import flashbot.models.Candle
import flashbot.util.json.CommonEncoders._
import io.circe.generic.JsonCodec

/**
  * Efficiently stores time series of candles.
  */
@JsonCodec
case class CandleFrame(time: debox.Buffer[Long] = debox.Buffer[Long](),
                       open: Buffer[Double] = debox.Buffer[Double](),
                       high: Buffer[Double] = debox.Buffer[Double](),
                       low: Buffer[Double] = debox.Buffer[Double](),
                       close: Buffer[Double] = debox.Buffer[Double](),
                       volume: Buffer[Double] = debox.Buffer[Double]()) {

  def size: Int = time.len

  def put(candle: Candle): Unit = {
    if (time.nonEmpty && candle.micros < time(size - 1))
      throw new RuntimeException(s"Outdated candle: $candle")

    // If it's the same time period, the new candle overwrites the last one.
    if (time.nonEmpty && candle.micros == time(size - 1)) {
      open(size - 1) = candle.open
      high(size - 1) = candle.high
      low(size - 1) = candle.low
      close(size - 1) = candle.close
      volume(size - 1) = candle.volume
    } else {
      time += candle.micros
      open += candle.open
      high += candle.high
      low += candle.low
      close += candle.close
      volume += candle.volume
    }
  }

  def insert(candle: Candle): Unit = {
    assert(time.isEmpty || time(size - 1) < candle.micros,
      "New candle must have a greater timestamp than the last candle")
    put(candle)
  }

  def replaceLast(candle: Candle): Unit = {
    assert(time.nonEmpty)
    assert(time(size - 1) == candle.micros,
      "Replacement candle must have same timestamp as last candle")
    put(candle)
  }
}
