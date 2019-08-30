package flashbot.models

import flashbot.core.{Priced, Timestamped}

case class Candle(micros: Long,
                  open: Double,
                  high: Double,
                  low: Double,
                  close: Double,
                  volume: Double) extends Timestamped with Priced {

  def mergeOHLC(candle: Candle): Candle =
    mergeOHLC(candle.open, candle.high, candle.low, candle.close, candle.volume)

  def mergePrice(price: Double): Candle = mergeOHLC(price, price, price, price, 0)

  def mergeTrade(price: Double, volume: Double): Candle = mergeOHLC(price, price, price, price, volume)

  def mergeOHLC(open: Double, high: Double, low: Double,
                close: Double, volume: Double): Candle = copy(
    high = this.high max high,
    low = this.low min low,
    close = close,
    volume = this.volume + volume
  )

  override def price = close
}

object Candle {
  import io.circe._
  import io.circe.generic.semiauto._

  implicit val candleEn: Encoder[Candle] = deriveEncoder
  implicit val candleDe: Decoder[Candle] = deriveDecoder

  def single(micros: Long, price: Double, volume: Double = 0): Candle =
    Candle(micros, price, price, price, price, volume)

  def empty(micros: Long): Candle = Candle.single(micros, 0)

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
