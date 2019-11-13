package flashbot.core

import debox.Buffer
import flashbot.models.Candle
import flashbot.util.json.CommonEncoders._
import io.circe.generic.JsonCodec
import spire.syntax.cfor._

/**
  * Efficiently stores time series of candles.
  */
@JsonCodec
case class CandleFrame(time: Buffer[Long] = debox.Buffer.empty[Long],
                       open: Buffer[Double] = debox.Buffer.empty[Double],
                       high: Buffer[Double] = debox.Buffer.empty[Double],
                       low: Buffer[Double] = debox.Buffer.empty[Double],
                       close: Buffer[Double] = debox.Buffer.empty[Double],
                       volume: Buffer[Double] = debox.Buffer.empty[Double]) {

  def size: Int = time.length

  def put(candle: Candle): Unit = {
    if (time.nonEmpty.&&(candle.micros < time(size - 1)))
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

  def toCandlesArray: Array[Candle] = {
    val candles = Array.ofDim[Candle](size)
    cfor(0)(_ < size, _ + 1) { i =>
      candles(i) = Candle(time(i), open(i), high(i), low(i), close(i), volume(i))
    }
    candles
  }
}

object CandleFrame {
  def empty = new CandleFrame()

  def apply(coll: Seq[Candle]): CandleFrame = {
    val frame = new CandleFrame()
    coll.foreach((x: Candle) =>
      frame.put(x)
    )
    frame
  }
}
