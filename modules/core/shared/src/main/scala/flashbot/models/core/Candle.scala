package flashbot.models.core

import flashbot.core.Num._
import flashbot.core.{Priced, Timestamped}

case class Candle(micros: Long,
                  open: Num,
                  high: Num,
                  low: Num,
                  close: Num,
                  volume: Num) extends Timestamped with Priced {

  def addOHLCV(open: Num, high: Num, low: Num,
               close: Num, volume: Num): Candle = copy(
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

  def single(micros: Long, price: Num, volume: Num = `0`) =
    Candle(micros, price, price, price, price, volume)

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
