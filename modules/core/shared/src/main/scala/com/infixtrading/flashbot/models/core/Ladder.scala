package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.Order.{Buy, Sell, Side}
import com.infixtrading.flashbot.core._
import io.circe._
import io.circe.generic.semiauto._

import scala.collection.immutable.{SortedMap, TreeMap}

case class Ladder(depth: Int,
                  asks: SortedMap[Double, Double] = TreeMap.empty,
                  bids: SortedMap[Double, Double] = TreeMap.empty(Ordering.by(-_)),
                  tickSize: Option[Double] = None) {
  import Ladder._

  assert(asks.isEmpty || asks.firstKey > asks.lastKey, "Asks out of order")
  assert(bids.isEmpty || bids.firstKey > bids.lastKey, "Bids out of order")

  def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder =
    side match {
      case Bid =>
        copy(bids = updateMap(bids, priceLevel, quantity))
      case Ask =>
        copy(asks = updateMap(asks, priceLevel, quantity))
    }

  def spread: Option[Double] = {
    if (asks.isEmpty || bids.isEmpty) None
    else Some(asks.firstKey - bids.firstKey)
  }

  def quantityAtPrice(price: Double): Option[Double] =
    asks.get(price).orElse(bids.get(price))

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
}


object Ladder {

  implicit val doubleKeyEncoder: KeyEncoder[Double] = new KeyEncoder[Double] {
    override def apply(key: Double): String = key.toString
  }

  implicit val doubleKeyDecoder: KeyDecoder[Double] = new KeyDecoder[Double] {
    override def apply(key: String): Option[Double] = Some(key.toDouble)
  }

  case class LadderDelta(side: QuoteSide, priceLevel: Double, quantity: Double)
  object LadderDelta {
    implicit val en: Encoder[LadderDelta] = deriveEncoder
    implicit val de: Decoder[LadderDelta] = deriveDecoder
  }

  implicit val ladderDecoder: Decoder[Ladder] = deriveDecoder[Ladder]
  implicit val ladderEncoder: Encoder[Ladder] = deriveEncoder[Ladder]

  implicit def ladderFmt: DeltaFmtJson[Ladder] = new DeltaFmtJson[Ladder] {
    override type D = LadderDelta

    override def fmtName = "ladder"

    override def update(model: Ladder, delta: D) = throw new NotImplementedError()

    override def diff(prev: Ladder, current: Ladder) = throw new NotImplementedError()

    override def fold(x: Ladder, y: Ladder) = throw new NotImplementedError()

    override def unfold(x: Ladder) = throw new NotImplementedError()

    override def modelEn = implicitly
    override def modelDe = implicitly
    override def deltaEn = implicitly
    override def deltaDe = implicitly
  }

  /**
    * Match an incoming order against an aggregated order book. Emit fills.
    * TODO: Add Time-In-Force controls such as Fill Or Kill.
    */
  def ladderFillOrder(book: Ladder,
                      side: Side,
                      sizeOpt: Option[Double],
                      fundsOpt: Option[Double],
                      limit: Option[Double] = None)
    : Seq[(Double, Double)] = {

    var fills = Seq.empty[(Double, Double)]
    (side, sizeOpt, fundsOpt) match {
      /**
        * Special case for when a market order is placed in terms of notional funds.
        * Does not support limits, crash if one is provided.
        */
      case (Buy, None, Some(funds)) =>
        if (limit.isDefined) {
          throw new RuntimeException("A limit order cannot be placed in terms of notional funds.")
        }

        val ladder = book.asks.toSeq.iterator
        var remainingFunds = funds
        while (remainingFunds > 0) {
          if (!ladder.hasNext) {
            throw new RuntimeException("Book not deep enough to fill order")
          }
          val (price, quantity) = ladder.next
          val min = math.min(remainingFunds, price * quantity)
          fills :+= (price, min / price)
          remainingFunds -= min
        }

      /**
        * The general case, for both market and limit orders that are placed in terms
        * of base quantity.
        */
      case (_, Some(size), None) =>
        val ladder = side match {
          case Buy =>
            book.asks.toSeq.iterator
          case Sell =>
            book.bids.toSeq.iterator
        }
        var remainingSize = size
        var limitExceeded = false
        while (remainingSize > 0 && !limitExceeded) {
          if (!ladder.hasNext) {
            throw new RuntimeException("Book not deep enough to fill order")
          }
          val (price, quantity) = ladder.next
          if (limit.isDefined) {
            limitExceeded = side match {
              case Buy => price > limit.get
              case Sell => price < limit.get
            }
          }
          if (!limitExceeded) {
            val min = math.min(remainingSize, quantity)
            fills :+= (price, min)
            remainingSize -= min
          }
        }
    }

    fills
  }

  def fromOrderBook(depth: Int)(book: OrderBook): Ladder = {
    Ladder(depth,
      asks = book.asks.take(depth).mapValues(_.map(_.amount).sum),
      bids = book.bids.take(depth).mapValues(_.map(_.amount).sum))
  }

  private def updateMap(map: SortedMap[Double, Double],
                        priceLevel: Double,
                        quantity: Double): SortedMap[Double, Double] =
    quantity match {
      case 0 => map - priceLevel
      case _ => map + (priceLevel -> quantity)
    }

  def takeQuantity(qty: Double, depths: SortedMap[Double, Double]): SortedMap[Double, Double] = {
    if (qty == 0) {
      depths
    } else if (qty < depths.head._2) {
      val newHead = (depths.head._1, depths.head._2)
      depths.tail + newHead
    } else {
      takeQuantity(qty - depths.head._2, depths.tail)
    }
  }

  def intersectDepths(left: SortedMap[Double, Double],
                      right: SortedMap[Double, Double]): SortedMap[Double, Double] = {
    var l = left
    var r = right
    var merged = TreeMap.empty[Double, Double](left.ordering)

    while (l.nonEmpty && r.nonEmpty) {
      val price = l.head._1 - r.head._1
      val qty = math.min(l.head._2, r.head._2)
      val quote = (price, qty)
      merged += quote
      l = takeQuantity(qty, l)
      r = takeQuantity(qty, r)
    }

    merged
  }
}
