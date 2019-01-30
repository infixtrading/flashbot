package flashbot.core

import java.util.UUID

import flashbot.models.core.Order.{Buy, Sell, Side}
import flashbot.models.core.{Order, OrderBook}

import scala.util.Random

/**
  * Generates random order book streams.
  */
object OrderBookTap {


  /**
    * 1. Build initial book with a random amount of orders of random sizes at each price level.
    * 2. On every iteration:
    *   a. Decide a price level to modify using a normal distribution.
    *   b. Choose a random order from that price level to operate on.
    *   c. Randomly decide if this is a "open", "change", or "cancel" event.
    */
  def apply(length: Long): Seq[OrderBook] = {
    var initialBook = OrderBook()
    var midpoint = 100
    var depth = 50
    val random = new Random()
    val normal = Gaussian(100, 10)
    def sideForPrice(price: Double): Side = if (price >= midpoint) Sell else Buy
    for (priceInt <- (midpoint - depth) to (midpoint + depth)) {
      val price = priceInt.toDouble
      for (_ <- (0 to random.nextInt(100)).drop(1)) {
        val size = random.nextDouble * 20
        initialBook = initialBook.open(UUID.randomUUID.toString, price, size, sideForPrice(price))
      }
    }

    def selectRandomOrder(book: OrderBook, price: Double): Option[Order] =
      book.asks.index.get(price).orElse(book.bids.index.get(price))
        .map(orders => orders(random.nextInt(orders.size)))

    (0 to length.toInt).scanLeft(initialBook) {
      case (book, _) =>
        val price = normal.draw().toInt.toDouble
        random.nextInt(3) match {
          // Open
          case 0 =>
            val size = random.nextDouble * 20
            book.open(UUID.randomUUID.toString, price, size, sideForPrice(price))

          // Change
          case 1 =>
            selectRandomOrder(book, price) match {
              case Some(order) => book.change(order.id, random.nextDouble * order.amount)
              case None => book
            }

          // Cancel
          case 2 =>
            selectRandomOrder(book, price) match {
              case Some(order) => book.done(order.id)
              case None => book
            }
        }
    }
  }
}
