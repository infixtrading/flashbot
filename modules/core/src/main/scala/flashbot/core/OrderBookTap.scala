package flashbot.core

import java.util.UUID
import breeze.stats.distributions.Gaussian
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.models.{Order, OrderBook}

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
  def apply(tickSize: Double, length: Long): Seq[OrderBook] = {
    var initialBook = new OrderBook(tickSize)
    var midpoint = 100
    var depth = 50
    val random = new Random()
    val normal = Gaussian(100, 10)
    def sideForPrice(price: Double): Side = if (price >= midpoint) Sell else Buy
    for (priceInt <- (midpoint - depth) to (midpoint + depth)) {
      val price = priceInt.toDouble
      for (_ <- (0 to random.nextInt(100)).drop(1)) {
        val size = random.nextDouble * 20
        initialBook.open(UUID.randomUUID.toString, price, size, sideForPrice(price))
      }
    }

    def selectRandomOrder(book: OrderBook, price: Double): Option[Order] = {
      val ordersIt = book.ordersAtPriceIterator(price)
      val size = ordersIt.size
      if (size >= 0) {
        val idx = if (size == 0) 0 else random.nextInt(ordersIt.size)
        Some(ordersIt.drop(idx).next())
      } else None
    }

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
