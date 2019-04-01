package flashbot.models

import flashbot.core.DeltaFmt.HasUpdateEvent
import flashbot.core._
import flashbot.models.Ladder.LadderDelta
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.util.{DoubleMap, NumberUtils}
import io.circe._
import io.circe.syntax._
import io.circe.generic.semiauto._
import it.unimi.dsi.fastutil.doubles.DoubleArrayFIFOQueue

/**
  * A ring buffer of the sizes at the top N levels of a side of a ladder.
  * FIFO is misleading here because it supports deque (double ended queue) ops.
  * Entries in the backing array may be 0. Accessor methods should hide this fact.
  * Additionally, this class is about as thread un-safe as you can get.
  *
  * @param maxDepth the number of price levels (not including empty levels) to support.
  * @param tickSize the price difference between consecutive levels.
  * @param side if this is a bid or ask ladder.
  */
class LadderSide(maxDepth: Int,
                 tickSize: Double,
                 side: QuoteSide)
    extends DoubleArrayFIFOQueue(maxDepth * 2) {

  assert(maxDepth > 0, "Max ladder depth must be > 0")

  val tickScale = NumberUtils.scale(tickSize)
  def round(price: Double) = NumberUtils.round(price, tickScale)

  // The number of non-empty levels in the ladder. This will be equal to `size` if
  // there are no empty levels between the best and worst.
  var depth: Int = 0

  var bestPrice: Double = java.lang.Double.NaN
  var worstPrice: Double = java.lang.Double.NaN

  def bestQty: Double = array(firstIndex)
  def worstQty: Double = array(lastIndex)

  /**
    * @param price the price level to update.
    * @param qty the qty at that price, or 0 to remove the price level.
    */
  def update(price: Double, qty: Double) = {
    val level = levelOfPrice(price)
    if (qty == 0) {
      assert(depth > 0, "Cannot remove level from empty ladder")
      val qtyToRemove = qtyAtLevel(level)

      assert(qtyToRemove > 0, s"Cannot remove empty level: $level")
      depth -= 1
      array(indexOfLevel(level)) = 0
      trimLevels()
    } else if (qty > 0) {
      // Base case. If depth is at zero, simply enqueue the qty.
      if (depth == 0) {
        enqueue(qty)
        bestPrice = price
        worstPrice = price
        depth = 1

      // If level < 0, then we need to prepend that many empty levels and set the
      // qty of the first level to the given qty.
      } else if (level < 0) {
        padLeft(-level)
        array(firstIndex) = qty
        depth += 1
        truncate()

      // If level >= size, then we need to append that many levels and set the qty
      // of the last level to the given qty.
      } else if (level >= size) {
        assert(depth < maxDepth, s"Can't add the level ($price, $qty) because the ladder is full.")
        padRight(level - size + 1)
        array(lastIndex) = qty
        depth += 1

      // Otherwise, we are updating an existing, possibly empty, level.
      } else {
        val existingQty = qtyAtLevel(level)
        if (existingQty == 0) {
          depth += 1
        }
        array(indexOfLevel(level)) = qty
        truncate()
      }
    } else
      throw new RuntimeException("Quantity cannot be set to a negative number")
  }

  private def firstIndex: Int = start
  private def lastIndex: Int = (if (end == 0) length else end) - 1

  // Removes elements from tail while depth exceeds the max depth.
  private def truncate(): Unit = {
    while (depth > maxDepth) {
      val removedQty = dequeueDouble()
      worstPrice = round(worstPrice - tickSize)
      if (removedQty > 0) {
        depth -= 1
      }
    }
    trimLevels()
  }

  // Ensures that `start` and `end` always point to non-empty levels.
  // Also updates the best and worst prices.
  private def trimLevels(): Unit = {
    if (depth == 0) {
      bestPrice = java.lang.Double.NaN
      worstPrice = java.lang.Double.NaN
    } else {
      while (firstDouble() == 0) {
        dequeueDouble()
        bestPrice = round(bestPrice + tickSize)
      }
      while (lastDouble() == 0) {
        dequeueLastDouble()
        worstPrice = round(worstPrice - tickSize)
      }
    }
  }

  private def padLeft(n: Int): Unit = {
    var i = 0
    while (i < n) {
      enqueueFirst(0)
      bestPrice = round(bestPrice - tickSize)
      i += 1
    }
  }

  private def padRight(n: Int): Unit = {
    var i = 0
    while (i < n) {
      enqueue(0)
      worstPrice = round(worstPrice + tickSize)
      i += 1
    }
  }

  def indexOfLevel(level: Int): Int = (start + level) % length

  def qtyAtLevel(level: Int): Double = array(indexOfLevel(level))

  def levelOfPrice(price: Double): Int =
    (math.round(price / tickSize) - math.round(bestPrice / tickSize)).toInt

  def priceOfLevel(level: Int): Double = round(bestPrice + level * tickSize)

  def nonEmpty = !isEmpty

  // Matches up until the price/size limits. Removes the filled liquidity from the ladder.
  // Returns the remaining unmatched qty.
  // Terminates the arrays with -1.
  def matchMutable(matchPrices: Array[Double],
                   matchQtys: Array[Double],
                   approxPriceLimit: Double,
                   approxSize: Double): Double = {
    val size = NumberUtils.round8(approxSize)
    val priceLimit = round(approxPriceLimit)
    var remainder = size
    var i = 0
    while (nonEmpty && remainder > 0 && bestPrice <= priceLimit) {
      val matchQty = math.min(remainder, bestQty)
      remainder = NumberUtils.round8(remainder - matchQty)
      matchPrices(i) = bestPrice
      matchQtys(i) = matchQty
      update(bestPrice, round(bestQty - matchQty))
      i += 1
    }
    matchPrices(i) = -1
    matchQtys(i) = -1
    remainder
  }


  // Like `matchMutable`, but doesn't remove the liquidity from the ladder itself.
  // Terminates the arrays with -1.
  def matchSilent(matchPrices: Array[Double],
                  matchQtys: Array[Double],
                  approxPriceLimit: Double,
                  approxSize: Double): Double = {
    val size = NumberUtils.round8(approxSize)
    val priceLimit = round(approxPriceLimit)
    var remainder = size
    var i = 0
    var break = false
    while (i < depth && remainder > 0 && !break) {
      val price = priceOfLevel(i)
      if (price <= priceLimit) {
        val qty = qtyAtLevel(i)
        val matchQty = math.min(remainder, qty)
        remainder = NumberUtils.round8(remainder - matchQty)
        matchPrices(i) = price
        matchQtys(i) = matchQty
        i += 1
      } else break = true
    }
    matchPrices(i) = -1
    matchQtys(i) = -1
    remainder
  }

  // Like `matchSilent`, but doesn't record individual matches. Instead computes
  // and returns (total size matched, unrounded avg price).
  def matchSilentAvg(approxPriceLimit: Double,
                     approxSize: Double): (Double, Double) = {
    val size = NumberUtils.round8(approxSize)
    val priceLimit = round(approxPriceLimit)
    var totalMatched = 0d
    var unroundedAvgPrice: Double = java.lang.Double.NaN
    var i = 0
    var break = false
    while (i < depth && !break) {
      val price = priceOfLevel(i)
      val remainder = NumberUtils.round8(size - totalMatched)
      if (price <= priceLimit && remainder > 0) {
        val qty = qtyAtLevel(i)
        val matchQty = math.min(remainder, qty)
        unroundedAvgPrice =
          if (java.lang.Double.isNaN(unroundedAvgPrice)) price
          else (unroundedAvgPrice * totalMatched + price * matchQty) / (totalMatched + matchQty)
        totalMatched = NumberUtils.round8(totalMatched + matchQty)
        i += 1
      } else break = true
    }
    (totalMatched, unroundedAvgPrice)
  }

  def forEach(fn: (Double, Double) => Unit): Unit = {
    var i = 0
    while (i < depth) {
      fn(priceOfLevel(i), qtyAtLevel(i))
      i += 1
    }
  }
}

class Ladder(val depth: Int, val tickSize: Double,
             private var asks: LadderSide = null,
             private var bids: LadderSide = null)
    extends HasUpdateEvent[Ladder, Seq[LadderDelta]] {

  if (asks == null) asks = new LadderSide(depth, tickSize, Ask)
  if (bids == null) bids = new LadderSide(depth, tickSize, Bid)

  val tickScale = NumberUtils.scale(tickSize)
  def round(price: Double) = NumberUtils.round(price, tickScale)

  def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder =
    side match {
      case Bid =>
        bids.update(priceLevel, quantity)
        this
      case Ask =>
        asks.update(priceLevel, quantity)
        this
    }

  def spread: Double = round(asks.bestPrice - bids.bestPrice)

//  def quantityAtPrice(price: Double): Double = {
//    if (asks.bestPrice)
//    var q = asks.get(price)
//    if (java.lang.Double.isNaN(q))
//      q = bids.get(price)
//    q
//  }

//  def map(fn: ((Double, Double)) => (Double, Double)): Ladder =
//    copy(asks = asks.map(fn), bids = bids.map(fn))

//  def aggregate(depth: Int, tickSize: Double): Ladder = {
//    throw new NotImplementedError()
//  }

//  def intersect(other: Ladder): Ladder = {
//    assert(depth == other.depth, "Only ladders of the same depth can be intersected.")
//    assert(tickSize == other.tickSize, "Only ladders with the same tick size can be intersected.")
//    assert(tickSize.isDefined, "Ladders must have a tick size to be intersected.")
//
//    copy(
//      asks = intersectDepths(asks, other.bids),
//      bids = intersectDepths(bids, other.asks)
//    )
//  }

//  def priceSet: Set[Double] = bids.keySet ++ asks.keySet

//  private def putOrder(order: Order): Ladder =
//    updateLevel(order.side.toQuote, order.price.get, order.amount)

  private val currentMatchPrices = Array[Double](200)
  private val currentMatchQtys = Array[Double](200)

  def foreachMatch(fn: (Double, Double) => Unit) = {
    var i = 0
    var matchPrice = currentMatchPrices(0)
    var matchQty = currentMatchQtys(0)
    while (matchPrice != -1 && matchQty != -1) {
      fn(matchPrice, matchQty)
      i += 1
      matchPrice = currentMatchPrices(i)
      matchQty = currentMatchQtys(i)
    }
  }

  def ladderFillOrder(book: Ladder, side: Side, size: Double, limit: Double, silent: Boolean): Double = {
    val depths: LadderSide = side match {
      case Buy => book.asks
      case Sell => book.bids
    }

    if (silent) depths.matchSilent(currentMatchPrices, currentMatchQtys, limit, size)
    else depths.matchMutable(currentMatchPrices, currentMatchQtys, limit, size)
  }

  override var lastUpdate: Option[Seq[LadderDelta]] = None

  override protected def withLastUpdate(d: Seq[LadderDelta]): Ladder = {
    lastUpdate = Some(d)
    this
  }

  override protected def step(deltas: Seq[LadderDelta]): Ladder = {
    deltas.foreach { d =>
      this.updateLevel(d.side, d.priceLevel, d.quantity)
    }
    this
  }
}


object Ladder {

//  implicit def doubleKeyEncoder: KeyEncoder[Double] = KeyEncoder.encodeKeyString.contramap(_.toString)
//  implicit def doubleKeyDecoder: KeyDecoder[Double] = KeyDecoder.decodeKeyString.map(_.toDouble)

  case class LadderDelta(side: QuoteSide, priceLevel: Double, quantity: Double)
  object LadderDelta {
    implicit val en: Encoder[LadderDelta] = Encoder.encodeTuple3[Int, Double, Double]
      .contramapArray(d => (d.side.toInt, d.priceLevel, d.quantity))
    implicit val de: Decoder[LadderDelta] = Decoder.decodeTuple3[Int, Double, Double]
      .map(t => LadderDelta(QuoteSide.fromInt(t._1), t._2, t._3))
  }

  implicit val ladderSideEncoder: Encoder[LadderSide] =
    Encoder.encodeSeq[LadderDelta].contramapArray(asks => {
      val buf = Array.ofDim[LadderDelta](asks.depth)
      var i = 0
      asks.forEach { (price, qty) =>
        buf(i) = LadderDelta(Ask, price, qty)
        i += 1
      }
      buf
    })

  implicit val ladderDecoder: Decoder[Ladder] = new Decoder[Ladder] {
    override def apply(c: HCursor) = for {
      depth <- c.downField("depth").as[Int]
      tickSize <- c.downField("tickSize").as[Double]
      ladder = new Ladder(depth, tickSize)
      askSeq <- c.downField("asks").as[Seq[LadderDelta]]
      bidSeq <- c.downField("bids").as[Seq[LadderDelta]]
    } yield ladder.step(askSeq).step(bidSeq)
  }

  implicit val ladderEncoder: Encoder[Ladder] = Encoder.encodeJsonObject
    .contramapObject(ladder => JsonObject(
      "depth" -> ladder.depth.asJson,
      "tickSize" -> ladder.tickSize.asJson,
      "asks" -> ladder.asks.asJson,
      "bids" -> ladder.bids.asJson
    ))

  implicit val ladderFmt: DeltaFmtJson[Ladder] =
    DeltaFmt.updateEventFmtJson("ladder")

//  implicit def ladderFmt: DeltaFmtJson[Ladder] = new DeltaFmtJson[Ladder] {
//    override type D = Seq[LadderDelta]
//
//    override def fmtName = "ladder"
//
//    override def update(model: Ladder, deltas: D) = {
//      model.update()
//      deltas.foldLeft(model) {
//        case (memo, LadderDelta(side, priceLevel, quantity)) =>
//          memo.updateLevel(side, priceLevel, quantity)
//      }
//    }
//
//    // This probably isn't great in terms of CPU usage. But it's probably fine.
////    override def diff(prev: Ladder, current: Ladder) = {
////      val remove = (prev.priceSet -- current.priceSet).map(p =>
////        if (prev.bids.isDefinedAt(p)) LadderDelta(Bid, p, 0)
////        else LadderDelta(Ask, p, 0))
////      val add = (current.priceSet -- prev.priceSet).map(p =>
////        if (current.bids.isDefinedAt(p)) LadderDelta(Bid, p, current.bids(p))
////        else LadderDelta(Ask, p, current.asks(p)))
////      val change = (current.priceSet intersect prev.priceSet).map(p =>
////        if (current.bids.isDefinedAt(p)) LadderDelta(Bid, p, current.bids(p))
////        else LadderDelta(Ask, p, current.asks(p)))
////      (remove ++ add ++ change).toSeq
////    }
//
//    override def fold(x: Ladder, y: Ladder) = y
//    override def unfold(x: Ladder) = (x, None)
//
//    override def modelEn = implicitly
//    override def modelDe = implicitly
//    override def deltaEn = implicitly
//    override def deltaDe = implicitly
//  }


//  private val emptyFills = mutable.ArrayBuffer.empty[Fill]
//  private var currentFills: mutable.ArrayBuffer[Fill] = emptyFills
//
//  private def resetFills() = currentFills = emptyFills
//  private def newFill(fill: Fill) = {
//    if (currentFills eq emptyFills)
//      currentFills = mutable.ArrayBuffer.empty[Fill]
//    currentFills += fill
//  }

  /**
    * Match an incoming order against an aggregated order book. Emit fills.
    * Returns the remainder.
    */
//  def ladderFillOrder(book: Ladder, side: Side, size: Double, limit: Double, silent: Boolean): Double = {
//
//    resetFills()
//
//    val depths: LadderSide = side match {
//      case Buy => book.asks
//      case Sell => book.bids
//    }
//
//    var remainingSize = size
//    var limitExceeded = false
//    while (remainingSize > 0 && !limitExceeded && depths.nonEmpty) {
//      val (price, quantity) = depths.head
//      val price = depths.bestPrice
//      val quantity = depths.bestQty
//      if (!java.lang.Double.isNaN(limit)) {
//        limitExceeded = side match {
//          case Buy => price > limit
//          case Sell => price < limit
//        }
//      }
//
//      if (!limitExceeded) {
//        val min = remainingSize min quantity
//        newFill(fillFactory(price, min))
//        remainingSize -= min
//      }
//    }
//
//    (currentFills, remainingSize)
//  }

//  def fromOrderBook(depth: Int)(book: OrderBook): Ladder =
//    book.stream.foldLeft(new Ladder(depth))(_ putOrder _)

//  private def updateMap(map: DoubleMap,
//                        priceLevel: Double,
//                        quantity: Double): DoubleMap =
//    {
//      if (java.lang.Double.isNaN(quantity))
//        throw new RuntimeException("Invalid order quantity NaN")
//      else if (quantity == 0)
//        map -= priceLevel
//      else
//        map += (priceLevel -> quantity)
//    }

//  def takeQuantity(qty: Num, depths: LadderSide): LadderSide = {
//    val headQty = depths.head._2
//    if (qty == `0`) {
//      depths
//    } else if (qty < headQty) {
//      depths += (depths.head._1 -> (headQty - qty))
//    } else {
//      depths -= depths.head._1
//      takeQuantity(qty - depths.head._2, depths.tail)
//    }
//  }

//  def intersectDepths(left: LadderSide, right: LadderSide): LadderSide = {
//    val merged = mutable.TreeMap.empty[Num, Num](left.ordering)
//
//    while (left.nonEmpty && right.nonEmpty) {
//      val price = left.head._1 - right.head._1
//      val qty = left.head._2 min right.head._2
//      val quote = (price, qty)
//      merged += quote
//      takeQuantity(qty, left)
//      takeQuantity(qty, right)
//    }
//
//    merged
//  }

}
