package flashbot.core

import debox.Buffer
import flashbot.models.Candle
import flashbot.util.json.CommonEncoders._
import io.circe.generic.JsonCodec

/**
  * Efficiently stores time series of candles.
  */
@JsonCodec
case class CandleFrame(timeSeries: debox.Buffer[Long] = debox.Buffer[Long](),
                       openSeries: Buffer[Double] = debox.Buffer[Double](),
                       highSeries: Buffer[Double] = debox.Buffer[Double](),
                       lowSeries: Buffer[Double] = debox.Buffer[Double](),
                       closeSeries: Buffer[Double] = debox.Buffer[Double](),
                       volumeSeries: Buffer[Double] = debox.Buffer[Double]()) {

  def size: Int = timeSeries.len

  def put(candle: Candle): Unit = {
    if (timeSeries.nonEmpty && candle.micros < timeSeries(size - 1))
      throw new RuntimeException(s"Outdated candle: $candle")

    // If it's the same time period, the new candle overwrites the last one.
    if (timeSeries.nonEmpty && candle.micros == timeSeries(size - 1)) {
      openSeries(size - 1) = candle.open
      highSeries(size - 1) = candle.high
      lowSeries(size - 1) = candle.low
      closeSeries(size - 1) = candle.close
      volumeSeries(size - 1) = candle.volume
    } else {
      timeSeries += candle.micros
      openSeries += candle.open
      highSeries += candle.high
      lowSeries += candle.low
      closeSeries += candle.close
      volumeSeries += candle.volume
    }
  }

  def insert(candle: Candle): Unit = {
    assert(timeSeries.isEmpty || timeSeries(size - 1) < candle.micros,
      "New candle must have a greater timestamp than the last candle")
    put(candle)
  }

  def replaceLast(candle: Candle): Unit = {
    assert(timeSeries.nonEmpty)
    assert(timeSeries(size - 1) == candle.micros,
      "Replacement candle must have same timestamp as last candle")
    put(candle)
  }
}
