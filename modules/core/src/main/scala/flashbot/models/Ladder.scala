package flashbot.models

import akka.NotUsed
import akka.stream.scaladsl.Flow
import flashbot.core._
import flashbot.core.DeltaFmt.HasUpdateEvent
import flashbot.models.Ladder.LadderDelta
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.util.{DoubleMap, NumberUtils}
import io.circe._
import io.circe.syntax._
import scala.collection.JavaConverters._

class Ladder(val depth: Int, val tickSize: Double,
             var asks: LadderSide = null,
             var bids: LadderSide = null)
  extends HasUpdateEvent[Ladder, Seq[LadderDelta]] with Matching {

  if (asks == null) asks = new LadderSide(depth, tickSize, Ask)
  if (bids == null) bids = new LadderSide(depth, tickSize, Bid)

  def copy(): Ladder = new Ladder(depth, tickSize, asks.copy(), bids.copy())

  def copyInto(that: Ladder): Unit = {
    assert(that.depth == depth)
    assert(that.tickSize == tickSize)
    asks.copyInto(that.asks)
    bids.copyInto(that.bids)
  }

  private val tickScale = NumberUtils.scale(tickSize)
  def round(price: Double): Double = NumberUtils.round(price, tickScale)

  def ladderSideFor(side: QuoteSide): LadderSide = if (side == Bid) bids else asks

  def ladderSideForTaker(side: Side): LadderSide = ladderSideFor(side.flip.toQuote)

  def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder = {
    ladderSideFor(side).update(priceLevel, quantity)
    this
  }

  def spread: Double = round(asks.bestPrice - bids.bestPrice)

  /**
    * @param side the side of the incoming, taker order
    * @param unroundedPrice price limit of taker order
    */
  def hasMatchingPrice(side: Side, unroundedPrice: Double): Boolean = {
    val quoteSide = side.flip.toQuote
    val ladder = ladderSideFor(quoteSide)
    quoteSide.isBetterOrEq(ladder.bestPrice, round(unroundedPrice))
  }


  private var currentError: OrderError = _

//  def matchMarket(side: Side, size: Double, silent: Boolean): Double = {
//    val qSide = side.flip.toQuote
//    val ladderSide = ladderSideFor(qSide)
//    if (silent) ladderSide.matchSilent(matchPrices, matchQtys, qSide.worst, size)
//    else ladderSide.matchMutable(matchPrices, matchQtys, qSide.worst, size)
//  }
//
//  def matchLimit(side: Side, price: Double, size: Double, silent: Boolean): Double = {
//    val ladderSide = ladderSideFor(side.flip.toQuote)
//    if (silent) ladderSide.matchSilent(matchPrices, matchQtys, price, size)
//    else ladderSide.matchMutable(matchPrices, matchQtys, price, size)
//  }


  override val lastUpdate: MutableOpt[Seq[LadderDelta]] = MutableOpt.from(None)

  override protected def _step(deltas: Seq[LadderDelta]): Ladder = {
    deltas.foreach { d =>
      this.updateLevel(d.side, d.priceLevel, d.quantity)
    }
    this
  }


  def qtyAtPrice(price: Double): Double =
    if (price >= asks.bestPrice)
      asks.qtyAtPrice(price)
    else bids.qtyAtPrice(price)



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
//    updateLevel(order.side.toQuote
  /**
    * Insert a limit order into the book. Match immediately whenever possible.
    * If immediate matches are possible, but `postOnly` is set, then set an error.
    */
//  def putOrder(side: Side,
//               size: Double,
//               limit: Double): Double = {
//    depths.matchMutable(currentMatchPrices, currentMatchQtys, limit, size)
//  }, order.price.get, order.amount)

  def sideOf(qs: QuoteSide): LadderSide = if (qs == Ask) asks else bids
  var lastMatchedSide: LadderSide = _

  override def matchPrices: Array[Double] = lastMatchedSide.matchPrices
  override def matchQtys: Array[Double] = lastMatchedSide.matchQtys

  override def matchMutable(quoteSide: QuoteSide,
                            approxPriceLimit: Double,
                            approxSize: Double): Double = {
    lastMatchedSide = sideOf(quoteSide)
    lastMatchedSide.matchMutable(quoteSide, approxPriceLimit, approxSize)
  }

  override def matchSilent(quoteSide: QuoteSide,
                           approxPriceLimit: Double,
                           approxSize: Double): Double = {
    lastMatchedSide = sideOf(quoteSide)
    lastMatchedSide.matchSilent(quoteSide, approxPriceLimit, approxSize)
  }

  override def matchSilentAvg(quoteSide: QuoteSide,
                              approxPriceLimit: Double,
                              approxSize: Double): (Double, Double) = {
    lastMatchedSide = sideOf(quoteSide)
    lastMatchedSide.matchSilentAvg(quoteSide, approxPriceLimit, approxSize)
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
    Encoder.encodeSeq[LadderDelta].contramapArray(ls => {
      val buf = Array.ofDim[LadderDelta](ls.depth)
      var p = java.lang.Double.NaN
      var i = 0
      while (ls.hasNextPrice(p)) {
        p = ls.nextPrice(p)
        val q = ls.qtyAtPrice(p)
        buf(i) = LadderDelta(ls.side, p, q)
        i += 1
      }
      buf
    })

  implicit val ladderDecoder: Decoder[Ladder] = new Decoder[Ladder] {
    override def apply(c: HCursor): Either[DecodingFailure, Ladder] = for {
      depth <- c.downField("depth").as[Int]
      tickSize <- c.downField("tickSize").as[Double]
      ladder = new Ladder(depth, tickSize)
      askSeq <- c.downField("asks").as[Seq[LadderDelta]]
      bidSeq <- c.downField("bids").as[Seq[LadderDelta]]
    } yield ladder._step(askSeq)._step(bidSeq)
  }

  implicit val ladderEncoder: Encoder[Ladder] = Encoder.encodeJsonObject
    .contramapObject(ladder => JsonObject(
      "depth" -> ladder.depth.asJson,
      "tickSize" -> ladder.tickSize.asJson,
      "asks" -> ladder.asks.asJson,
      "bids" -> ladder.bids.asJson
    ))

  implicit val ladderFmt: DeltaFmtJson[Ladder] =
    DeltaFmt.updateEventFmtJson[Ladder, Seq[LadderDelta]]("ladder")


  def fromOrderBook(depth: Int, book: OrderBook): Ladder = {
    val ladder = new Ladder(depth, book.tickSize)
    for (p <- book.asks.priceIterator.take(depth)) {
      val sum = book.ordersAtPrice(p).values().iterator().asScala.map(_.amount).sum
      ladder.updateLevel(Ask, p, sum)
    }
    for (p <- book.bids.priceIterator.take(depth)) {
      val sum = book.ordersAtPrice(p).values().iterator().asScala.map(_.amount).sum
      ladder.updateLevel(Bid, p, sum)
    }
    ladder
  }

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
