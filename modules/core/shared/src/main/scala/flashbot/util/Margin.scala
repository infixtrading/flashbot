package flashbot.util

import flashbot.core.Instrument.Derivative
import flashbot.models.core.{Order, OrderBook}

object Margin {

  /**
    * ====================================================================
    *   Below is a reference implementation of order margin calculation.
    *   Includes println test cases that are verified against BitMEX.
    * ====================================================================
    */

  def calcOrderMargin(pos: Double, leverage: Double,
                      book: OrderBook, instrument: Derivative): Double = {
    val asks = book.asks.index.toSeq.flatMap(_._2)
    val bids = book.bids.index.toSeq.flatMap(_._2)

    val (primary, secondary) =
      if (pos >= 0) (asks, bids)
      else (bids, asks)

    def sum(seq: Seq[Order], fn: Order => Double) = {
      val x = seq.map(fn).sum.abs
      if (seq.isEmpty) 0.0 else x
    }

    def percent(remainder: Double, total: Double) =
      if (total == 0) 0.0 else remainder / total

    def weight(value: Double, qty: Double) =
      if (qty == 0) 0.0 else value / qty

    def merge(valA: Double, qtyA: Double, valB: Double, qtyB: Double) = {
      val (hv, hq, lv, lq) =
        if (weight(valA, qtyA) > weight(valB, qtyB))
          (valA, qtyA, valB, qtyB)
        else (valB, qtyB, valA, qtyA)
      val rem = math.max(lq - hq, 0)
      val perc = percent(rem, lq)
      hv + perc * lv
    }

    def orderValue(order: Order) =
      instrument.value(order.price.get).amount * order.amount

    val primaryQty = sum(primary, _.amount)
    val secondaryQty = sum(secondary, _.amount)

    val primaryVal = sum(primary, orderValue)
    val secondaryVal = sum(secondary, orderValue)

    val primaryRemainder: Double = math.max(primaryQty - pos.abs, 0.0)
    val primaryPercent: Double = percent(primaryRemainder, primaryQty)

    val primaryWeightedVal = primaryVal * primaryPercent
    val merged = merge(primaryWeightedVal, primaryRemainder, secondaryVal, secondaryQty)
    merged / leverage
  }
}
