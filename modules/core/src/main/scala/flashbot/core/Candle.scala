package flashbot.core

case class Candle(micros: Long,
                  open: Double,
                  high: Double,
                  low: Double,
                  close: Double,
                  volume: Double) extends Timestamped with Priced {

  def addOHLCV(open: Double, high: Double, low: Double,
               close: Double, volume: Double): Candle = copy(
    high = this.high max high,
    low = this.low min low,
    close = close,
    volume = this.volume + volume
  )

  override def price = close
}
