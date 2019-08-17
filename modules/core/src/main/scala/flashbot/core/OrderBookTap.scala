package flashbot.core

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.util.{Date, UUID}

import akka.NotUsed
import akka.stream.scaladsl.Source
import breeze.stats.distributions.Gaussian
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.models.{Candle, Ladder, Order, OrderBook, TimeRange}
import flashbot.util.{NumberUtils, TableUtil}
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.{BaseBar, BaseTimeSeries, TimeSeries, num}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

/**
  * Generates random order book streams.
  */
object OrderBookTap {

  //
  // =============================================
  //      Quote imbalance transition matrix
  // =============================================
  //             Bearish    Balanced     Bullish
  //   Bearish     .7         .05          .25
  //   Balanced    .25        .5           .25
  //   Bullish     .25        .05          .7
  // =============================================
  //

  sealed trait ArrivalRateImbalance
  case object Bearish extends ArrivalRateImbalance
  case object Balanced extends ArrivalRateImbalance
  case object Bullish extends ArrivalRateImbalance

  def transition(from: ArrivalRateImbalance, to: ArrivalRateImbalance): Double = {
    (from, to) match {
      case (Balanced, Balanced) => .5
      case (Balanced, _) => .25
      case (a, Balanced) => .05
      case (a, b) if a == b => .7
      case (a, b) if a != b => .25
    }
  }

  /**
    * 1. Build initial book with a random amount of orders of random sizes at each price level.
    * 2. On every iteration:
    *   a. Decide a price level to modify using a normal distribution.
    *   b. Choose a random order from that price level to operate on.
    *   c. Randomly decide if this is a "open", "change", or "cancel" event.
    */
  def apply(tickSize: Double, limit: Int = 0): Stream[OrderBook] = {
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

    val stream = Stream.from(0).scanLeft(initialBook) {
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

    if (limit == 0) stream else stream.take(limit)
  }

  def apply(startPrice: Double, tickSize: Double, mu: Double, sigma: Double, smaBars: Int): Source[(Date, (Double, Double)), NotUsed] = {
    val now = Instant.now
    val zdtNow = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
    val timeRange = TimeRange.build(now, "24h", "now")
    val timeSeries = new BaseTimeSeries.SeriesBuilder()
      .withName("reference_prices")
      .withMaxBarCount(500)
      .withNumTypeOf(num.DoubleNum.valueOf(_))
      .build()

    lazy val close = new ClosePriceIndicator(timeSeries)
    lazy val sma = new SMAIndicator(close, 14)
    val referencePrices = TimeSeriesTap
      .prices(startPrice, mu, sigma, timeRange, 1 minute, infinite = true)
      .via(TimeSeriesTap.aggregatePrices(1 hour))
      .scan((timeSeries, Balanced, new Ladder(25, 1d))) {
        case ((_, arrivalRateImbalance, ladder), candle: Candle) =>

          // TODO
          // On every candle, step forward the ArrivalImbalance markov process


          // Update the time series
          val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(candle.micros / 1000), ZoneOffset.UTC)
          timeSeries.addBar(java.time.Duration.ofHours(1), zdt)
          timeSeries.addPrice(candle.open)
          timeSeries.addPrice(candle.high)
          timeSeries.addPrice(candle.low)
          timeSeries.addPrice(candle.close)
          (timeSeries, Balanced, ladder)
      }
      .drop(1)
      .filterNot(ts => ts._1.getLastBar.getEndTime.isBefore(zdtNow))
      .map { ts =>
        (Date.from(ts._1.getLastBar.getEndTime.toInstant), (
          ts._1.getLastBar.getClosePrice.getDelegate.doubleValue(),
          sma.getValue(ts._1.getEndIndex).getDelegate.doubleValue())
        )
      }

    referencePrices
  }

  def simpleLadderSimulation(): Iterator[Ladder] = {
    val initialLadder: Ladder = new Ladder(200, 1)

    val initialGauss = Gaussian(100, 10)
    for (i <- 0 to 10) {
      val p = initialGauss.draw().intValue()
      initialLadder.updateLevel(if (p <= 100) Bid else Ask, p, 1)
    }

    val random = new Random()
    val gauss = Gaussian(0, 5)

    Iterator.from(0)
      .scanLeft(initialLadder) {
        case (ladder, _) =>
          // Order arrival flow
          val quoteSide = if (random.nextBoolean()) Bid else Ask
          val ladderSide = ladder.ladderSideFor(quoteSide)
          val otherSide = ladder.ladderSideFor(quoteSide.flip)
          val referencePrice =
            if (otherSide.nonEmpty) otherSide.bestPrice
            else if (ladderSide.nonEmpty) ladderSide.bestPrice
            else 100
          val delta = Math.abs(gauss.draw().intValue())
          val price = quoteSide.makeWorseBy(referencePrice, delta)
          ladder.updateLevel(quoteSide, price, ladder.qtyAtPrice(price) + 1)

          // Market order flow and cancellations
          if (random.nextInt(10) == 0) {
            val n = random.nextInt(100)
            if (n < 5) {
              val tradeAmt = 10
              val qSide = if (random.nextBoolean()) Bid else Ask
              ladder.matchMutable(qSide, qSide.worst, tradeAmt)

              if (ladder.asks.bestPrice < ladder.bids.bestPrice) {
                throw new RuntimeException("Invalid ladder")
              }
            } else {
              // Random order cancellations
              val cancelSide = ladder.ladderSideFor(if (random.nextBoolean()) Bid else Ask)
              var leftToCancel = 10d
                while (cancelSide.nonEmpty && leftToCancel > 0) {
                  val priceToCancel = cancelSide.randomPriceLevelWeighted
                  val existingQty = cancelSide.qtyAtPrice(priceToCancel)
                  val newQty = NumberUtils.round8(math.max(existingQty - leftToCancel, 0))
                  ladder.updateLevel(cancelSide.side, priceToCancel, newQty)
                  leftToCancel = NumberUtils.round8(leftToCancel - (existingQty - newQty))
                }
            }
          }

          ladder
      }
  }
}
