package flashbot.util

import flashbot.core.Instrument.Derivative
import flashbot.core.Num._
import flashbot.models.core.{Order, OrderBook}

object Margin {

  /**
    * ====================================================================
    *   Below is a reference implementation of order margin calculation.
    *   Includes println test cases that are verified against BitMEX.
    * ====================================================================
    */

  def calcOrderMargin(pos: Num, leverage: Num,
                      book: OrderBook, instrument: Derivative): Num = {
    val asks = book.asks.index.toSeq.flatMap(_._2)
    val bids = book.bids.index.toSeq.flatMap(_._2)

    val (primary, secondary) =
      if (pos >= `0`) (asks, bids)
      else (bids, asks)

    def sum(seq: Seq[Order], fn: Order => Num): Num = {
      val x = seq.map(fn).sum.abs
      if (seq.isEmpty) `0` else x
    }

    def percent(remainder: Num, total: Num) =
      if (total == `0`) `0` else remainder / total

    def weight(value: Num, qty: Num): Num =
      if (qty == `0`) `0` else value / qty

    def merge(valA: Num, qtyA: Num, valB: Num, qtyB: Num) = {
      val (hv, hq, lv, lq) =
        if (weight(valA, qtyA) > weight(valB, qtyB))
          (valA, qtyA, valB, qtyB)
        else (valB, qtyB, valA, qtyA)
      val rem = (lq - hq) max `0`
      val perc = percent(rem, lq)
      hv + perc * lv
    }

    def orderValue(order: Order) =
      instrument.value(order.price.get) * order.amount

    val primaryQty = sum(primary, _.amount)
    val secondaryQty = sum(secondary, _.amount)

    val primaryVal = sum(primary, orderValue)
    val secondaryVal = sum(secondary, orderValue)

    val primaryRemainder = (primaryQty - pos.abs) max `0`
    val primaryPercent = percent(primaryRemainder, primaryQty)

    val primaryWeightedVal = primaryVal * primaryPercent
    val merged = merge(primaryWeightedVal, primaryRemainder, secondaryVal, secondaryQty)
    merged / leverage
  }
}
