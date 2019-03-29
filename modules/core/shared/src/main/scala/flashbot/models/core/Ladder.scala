package flashbot.models.core

import flashbot.core._
import flashbot.models.core.Order.{Buy, Fill, Sell, Side}
import flashbot.util.DoubleMap
import io.circe._
import io.circe.generic.semiauto._

import scala.collection.mutable

class Ladder(val depth: Int, val tickSize: Option[Double] = None) {
  import Ladder._

  private val asks: DoubleMap = new DoubleMap(depth, reverse = false)
  private val bids: DoubleMap = new DoubleMap(depth, reverse = true)

  def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder =
    side match {
      case Bid =>
        updateMap(bids, priceLevel, quantity)
        this
      case Ask =>
        updateMap(asks, priceLevel, quantity)
        this
    }

  def increaseLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder =
    updateLevel(side, priceLevel, quantityAtPrice(priceLevel) + quantity)

  def spread: Double = asks.firstKey - bids.firstKey

  def quantityAtPrice(price: Double): Double = {
    var q = asks.get(price)
    if (java.lang.Double.isNaN(q))
      q = bids.get(price)
    q
  }

  def map(fn: ((Double, Double)) => (Double, Double)): Ladder =
    copy(asks = asks.map(fn), bids = bids.map(fn))

  def aggregate(depth: Int, tickSize: Double): Ladder = {
    throw new NotImplementedError()
  }

  def intersect(other: Ladder): Ladder = {
    assert(depth == other.depth, "Only ladders of the same depth can be intersected.")
    assert(tickSize == other.tickSize, "Only ladders with the same tick size can be intersected.")
    assert(tickSize.isDefined, "Ladders must have a tick size to be intersected.")

    copy(
      asks = intersectDepths(asks, other.bids),
      bids = intersectDepths(bids, other.asks)
    )
  }

  def priceSet: Set[Double] = bids.keySet ++ asks.keySet

  private def putOrder(order: Order): Ladder =
    updateLevel(order.side.toQuote, order.price.get, order.amount)
}


object Ladder {

  implicit def doubleKeyEncoder: KeyEncoder[Double] = KeyEncoder.encodeKeyString.contramap(_.toString)
  implicit def doubleKeyDecoder: KeyDecoder[Double] = KeyDecoder.decodeKeyString.map(_.toDouble)

  case class LadderDelta(side: QuoteSide, priceLevel: Double, quantity: Double)
  object LadderDelta {
    implicit val en: Encoder[LadderDelta] = deriveEncoder
    implicit val de: Decoder[LadderDelta] = deriveDecoder
  }

  implicit val ladderDecoder: Decoder[Ladder] = deriveDecoder[Ladder]
  implicit val ladderEncoder: Encoder[Ladder] = deriveEncoder[Ladder]

  implicit def ladderFmt: DeltaFmtJson[Ladder] = new DeltaFmtJson[Ladder] {
    override type D = Seq[LadderDelta]

    override def fmtName = "ladder"

    override def update(model: Ladder, deltas: D) = {
      deltas.foldLeft(model) {
        case (memo, LadderDelta(side, priceLevel, quantity)) =>
          memo.updateLevel(side, priceLevel, quantity)
      }
    }

    // This probably isn't great in terms of CPU usage. But it's probably fine.
    override def diff(prev: Ladder, current: Ladder) = {
      val remove = (prev.priceSet -- current.priceSet).map(p =>
        if (prev.bids.isDefinedAt(p)) LadderDelta(Bid, p, 0)
        else LadderDelta(Ask, p, 0))
      val add = (current.priceSet -- prev.priceSet).map(p =>
        if (current.bids.isDefinedAt(p)) LadderDelta(Bid, p, current.bids(p))
        else LadderDelta(Ask, p, current.asks(p)))
      val change = (current.priceSet intersect prev.priceSet).map(p =>
        if (current.bids.isDefinedAt(p)) LadderDelta(Bid, p, current.bids(p))
        else LadderDelta(Ask, p, current.asks(p)))
      (remove ++ add ++ change).toSeq
    }

    override def fold(x: Ladder, y: Ladder) = y
    override def unfold(x: Ladder) = (x, None)

    override def modelEn = implicitly
    override def modelDe = implicitly
    override def deltaEn = implicitly
    override def deltaDe = implicitly
  }

  private val emptyFills = mutable.ArrayBuffer.empty[Fill]
  private var currentFills: mutable.ArrayBuffer[Fill] = emptyFills

  private def resetFills() = currentFills = emptyFills
  private def newFill(fill: Fill) = {
    if (currentFills eq emptyFills)
      currentFills = mutable.ArrayBuffer.empty[Fill]
    currentFills += fill
  }

  /**
    * Match an incoming order against an aggregated order book. Emit fills.
    * Returns the fills and the remainder.
    * TODO: Add Time-In-Force controls such as Fill Or Kill.
    */
  def ladderFillOrder(book: Ladder, side: Side, size: Double, funds: Double, limit: Double = Double.NaN)
                     (fillFactory: (Double, Double) => Fill)
    : (mutable.Buffer[Fill], Double) = {

    resetFills()

    val depths: LadderSide = side match {
      case Buy => book.asks
      case Sell => book.bids
    }

    var remainingSize = size
    var limitExceeded = false
    while (remainingSize > `0` && !limitExceeded && depths.nonEmpty) {
      val (price, quantity) = depths.head
      if (limit.isValid) {
        limitExceeded = side match {
          case Buy => price > limit
          case Sell => price < limit
        }
      }

      if (!limitExceeded) {
        val min = remainingSize min quantity
        newFill(fillFactory(price, min))
        remainingSize -= min
      }
    }

    (currentFills, remainingSize)
  }

  def fromOrderBook(depth: Int)(book: OrderBook): Ladder =
    book.stream.foldLeft(new Ladder(depth))(_ putOrder _)

  private def updateMap(map: DoubleMap,
                        priceLevel: Double,
                        quantity: Double): DoubleMap =
    {
      if (java.lang.Double.isNaN(quantity))
        throw new RuntimeException("Invalid order quantity NaN")
      else if (quantity == 0)
        map -= priceLevel
      else
        map += (priceLevel -> quantity)
    }

  def takeQuantity(qty: Num, depths: LadderSide): LadderSide = {
    val headQty = depths.head._2
    if (qty == `0`) {
      depths
    } else if (qty < headQty) {
      depths += (depths.head._1 -> (headQty - qty))
    } else {
      depths -= depths.head._1
      takeQuantity(qty - depths.head._2, depths.tail)
    }
  }

  def intersectDepths(left: LadderSide, right: LadderSide): LadderSide = {
    val merged = mutable.TreeMap.empty[Num, Num](left.ordering)

    while (left.nonEmpty && right.nonEmpty) {
      val price = left.head._1 - right.head._1
      val qty = left.head._2 min right.head._2
      val quote = (price, qty)
      merged += quote
      takeQuantity(qty, left)
      takeQuantity(qty, right)
    }

    merged
  }
}
