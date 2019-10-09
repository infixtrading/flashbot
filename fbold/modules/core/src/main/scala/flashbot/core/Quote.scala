package flashbot.core

case class Quote(bidPrice: Double,
                 bidAmount: Double,
                 askPrice: Double,
                 askAmount: Double) {
  def reverse: Quote = Quote(
    bidPrice = askPrice,
    bidAmount = askAmount,
    askPrice = bidPrice,
    askAmount = bidAmount
  )
}

