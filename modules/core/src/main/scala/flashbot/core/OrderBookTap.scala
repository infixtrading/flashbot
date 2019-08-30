package flashbot.core

import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.util
import java.util.{Date, UUID}

import akka.NotUsed
import akka.stream.scaladsl.Source
import breeze.stats.distributions.Gaussian
import flashbot.core.OrderBookTap.QuoteImbalance.{Balanced, Bearish, Bullish}
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.models.{Candle, Ladder, Order, OrderBook, TimeRange}
import flashbot.util.{NumberUtils, TableUtil}
import flashbot.util.time._
import org.ta4j.core.indicators.SMAIndicator
import org.ta4j.core.indicators.helpers.ClosePriceIndicator
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator
import org.ta4j.core.{BaseBar, BaseTimeSeries, TimeSeries, num}

import scala.collection.mutable.ArrayBuffer
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
  //   Bearish     .5         .35          .15
  //   Balanced    .25        .5           .25
  //   Bullish     .15        .35          .5
  // =============================================
  //

  sealed trait QuoteImbalance {

    private val random = new Random()

    def transitionRatio(from: QuoteImbalance, to: QuoteImbalance): Double = {
      (from, to) match {
        case (Balanced, Balanced) => .5
        case (Balanced, _) => .25

        case (_, Balanced) => .35
        case (a, b) if a == b => .5
        case (a, b) if a != b => .15
      }
    }

    def states: Set[QuoteImbalance] = Set(Bearish, Balanced, Bullish)

    def step(): QuoteImbalance = {
      var ratioSum = 0D
      var next: Option[QuoteImbalance] = None
      var rand = random.nextDouble()
      states.foreach { state =>
        val ratio = transitionRatio(this, state)
        ratioSum += ratio
        if (next.isEmpty && rand < ratioSum) {
          next = Some(state)
        }
      }
      assert(NumberUtils.round8(ratioSum) == 1.0,
        s"Markov transition ratio sums for $this must equal 1.0")
      next.get
    }

    def value: Double
  }

  object QuoteImbalance {
    case object Bearish extends QuoteImbalance {
      override def value = .2
    }
    case object Balanced extends QuoteImbalance {
      override def value = .5
    }
    case object Bullish extends QuoteImbalance {
      override def value = .8
    }

    def detectImbalance(buyLiquidity: Double, sellLiquidity: Double): Double =
      buyLiquidity / (buyLiquidity + sellLiquidity)
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
    val referencePrices = PriceTap
      .akkaStream(startPrice, mu, sigma, timeRange, 1 minute, infinite = true)
      .via(PriceTap.aggregatePricesFlow(1 hour))
      .scan((timeSeries, Balanced, new Ladder(25, 1d))) {
        case ((_, arrivalRateImbalance, ladder), candle: Candle) =>

          // TODO
          // On every candle, step forward the ArrivalImbalance markov process


          // Update the time series
          timeSeries.addBar(java.time.Duration.ofHours(1), candle.zdt)
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

//  def guidedBookSimulation(referencePrices: Iterable[Candle]): Iterator[(Long, Ladder, Double)] = {
//  }


  def simpleLadderSimulation(referencePrices: Iterator[(Instant, Double)],
                             preProcessDuration: Duration = 1 day,
                             maxPreProcessBarCount: Int = Int.MaxValue): Iterator[(Long, Ladder, Double)] = {

    val (preProcessIt, liveRefPricesIt) =
      preProcessIteratorSplit(referencePrices, preProcessDuration)

    val random = new Random()
    val gauss = Gaussian(0, 15)
    val gaussTradeCoeff = Gaussian(10, 3)

    /**
      * Preprocess the reference prices
      */
    lazy val preProcessBarCount = math.min(
      (preProcessDuration.toMillis / preProcessIt.timeStepMillis).intValue(),
      maxPreProcessBarCount)
    lazy val _preProcessTimeSeries = new BaseTimeSeries.SeriesBuilder()
      .withName("reference_prices")
      .withMaxBarCount(preProcessBarCount)
      .withNumTypeOf(num.DoubleNum.valueOf(_))
      .build()

    lazy val _close = new ClosePriceIndicator(_preProcessTimeSeries)
    lazy val _stdDev = new StandardDeviationIndicator(_close, preProcessBarCount)

    preProcessIt.foreach {
      case (inst, price) =>
        val zdt = ZonedDateTime.ofInstant(inst, ZoneOffset.UTC)
        _preProcessTimeSeries.addBar(java.time.Duration.ofMillis(preProcessIt.timeStepMillis), zdt)
        _preProcessTimeSeries.addPrice(price)
        println(inst, price)
    }

    val stdDev = _stdDev.getValue(_stdDev.getTimeSeries.getEndIndex)

    println(s"Standard deviation: $stdDev")
    throw new RuntimeException("foo")

    def ladderIteratorForRefPrice(ladder: Ladder,
                                  currentMillis: Long,
                                  referenceMillis: Long,
                                  referencePrice: Double): Iterator[(Long, Ladder)] = {
      ???
    }



    val initialLadder: Ladder = new Ladder(2000, .5)
    var currentRefMillis: Long = -1


    var currentImbalance: Double = -1
    var targetImbalance: QuoteImbalance = Balanced

    def addLiquidity(ladder: Ladder,
                     ratio: Double = .5,
                     width: Double = 1.0): Unit = {
      val quoteSide = if (random.nextDouble() < ratio) Bid else Ask
      val ladderSide = ladder.ladderSideFor(quoteSide)
      val otherSide = ladder.ladderSideFor(quoteSide.flip)
      var delta: Double = ladder.roundToTick(Math.abs(gauss.draw()) * width)
      val referencePrice: Double =
        if (otherSide.nonEmpty) {
          delta += ladder.tickSize
          otherSide.bestPrice
        }
        else if (ladderSide.nonEmpty) ladderSide.bestPrice
        else ???
      val price = Math.max(quoteSide.makeWorseBy(referencePrice, delta), ladder.tickSize)
      ladder.updateLevel(quoteSide, price, ladder.qtyAtPrice(price) + 1)
    }

    for (_ <- 0 to 100) {
      addLiquidity(initialLadder)
    }

    var lastSecond: Long = currentRefMillis / 1000

    Iterator.from(0)
      .scanLeft((currentRefMillis * 1000, initialLadder, ???)) {
        case ((_, ladder, _), i) =>
          val currentMillis = currentRefMillis + i
          val currentSecond: Long = currentMillis / 1000

          // Get the reference price
//          refPrice = nextRefPrice(currentMillis)

          // Detect current imbalance
          currentImbalance = QuoteImbalance.detectImbalance(ladder.bids.totalQty, ladder.asks.totalQty)

          // Every second, step the markov process
          if (lastSecond != currentSecond) {
            targetImbalance = targetImbalance.step()
          }
          lastSecond = currentSecond

          val currentHour: Long = currentSecond / 60 / 60

          val vol = ((currentHour % 3) + 1) * 10

//          if (currentHour % 4 < 2) {
//            addLiquidity(ladder, .5 + (targetImbalance.value - currentImbalance) / 5, .98)
//          } else {
            addLiquidity(ladder, .5 + (targetImbalance.value - currentImbalance) / 5)

//          if (random.nextInt(3) == 0)
//            addLiquidity(ladder, 1)

          // Market order flow and cancellations
          if (random.nextInt(10) == 0) {
            val n = random.nextInt(100)
            if (n < vol) {
              val tradeMultiplier = 30
              if (random.nextInt(tradeMultiplier) == 0) {
                val tradeAmt = gaussTradeCoeff.draw() * tradeMultiplier
                val qSide = if (random.nextBoolean()) Bid else Ask
                ladder.matchMutable(qSide, qSide.worst, tradeAmt)
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

          (currentMillis * 1000, ladder, ???)
      }
  }

  trait TimeSeriesIterator[X, Y] extends Iterator[(X, Y)] {
    var timeStepMillis: Long
  }

  private def preProcessIteratorSplit(referencePrices: Iterator[(Instant, Double)],
                                      preProcessDuration: Duration)
    : (TimeSeriesIterator[Instant, Double], Iterator[(Instant, Double)]) = {

    val preProcessIt: TimeSeriesIterator[Instant, Double] = new TimeSeriesIterator[Instant, Double] {
      override var timeStepMillis: Long = -1

      var lastSeenMillis: Long = -1
      var startMillis: Long = -1

      var buf: ArrayBuffer[(Instant, Double)] = new ArrayBuffer[(Instant, Double)](3)

      private def _bufferNext(): Unit = {
        if (_hasNext)
          buf += _next()
      }

      // Buffer next twice to ensure timeStepMillis is populated
      _bufferNext()
      _bufferNext()

      private def _hasNext: Boolean = {
        val preProcessedMillis = if (startMillis != -1) lastSeenMillis - startMillis else -1
        if (!referencePrices.hasNext) {
          if (preProcessedMillis == -1)
            throw new RuntimeException("Reference prices must have at least 2 entries")
          if (preProcessedMillis < preProcessDuration.toMillis)
            throw new RuntimeException(s"Reference prices must have at least $preProcessDuration of data")
          false
        } else {
          preProcessedMillis == -1 || (preProcessedMillis + timeStepMillis <= preProcessDuration.toMillis)
        }
      }

      private def _next(): (Instant, Double) = {
        val (inst, refPrice) = referencePrices.next()
        val millis = inst.toEpochMilli

        if (lastSeenMillis != -1) {
          val stepMillis = millis - lastSeenMillis
          if (timeStepMillis != -1 && timeStepMillis != stepMillis) {
            throw new RuntimeException("Reference prices must be evenly spaced in time")
          }
          timeStepMillis = stepMillis
        }

        // Populate start millis
        if (timeStepMillis != -1 && startMillis == -1)
          startMillis = lastSeenMillis - timeStepMillis

        lastSeenMillis = millis
        (inst, refPrice)
      }

      override def hasNext: Boolean = buf.nonEmpty

      override def next(): (Instant, Double) = {
        _bufferNext()
        buf.remove(0)
      }
    }

    assert(preProcessIt.timeStepMillis != -1)

    (preProcessIt, referencePrices)
  }


}
