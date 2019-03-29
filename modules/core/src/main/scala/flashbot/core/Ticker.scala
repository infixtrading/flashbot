package flashbot.core

case class Ticker(micros: Long,
                  bestBidPrice: Double,
                  bestBidQuantity: Double,
                  bestAskPrice: Double,
                  bestAskQuantity: Double,
                  lastTradePrice: Double,
                  lastTradeId: Long) extends Timestamped with Priced {
  def price: Double = lastTradePrice
}
