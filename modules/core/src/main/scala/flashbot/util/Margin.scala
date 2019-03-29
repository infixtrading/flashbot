package flashbot.util

import flashbot.core.Instrument
import flashbot.core.Instrument.Derivative
import flashbot.models.core.{Order, OrderBook}
import spire.syntax.cfor._
import NumberUtils._

object Margin {

  def calcOrderMargin(pos: Double, leverage: Double,
                      book: OrderBook, instrument: Derivative): Double = {

    val asksArray = book.asksArray
    val bidsArray = book.bidsArray

    val (primary, secondary) =
      if (pos >= 0) (asksArray, bidsArray)
      else (bidsArray, asksArray)

    val primaryQty = sum(primary, orderAmount)
    val secondaryQty = sum(secondary, orderAmount)

    val valueFn = orderValue(instrument)
    val primaryVal = sum(primary, valueFn)
    val secondaryVal = sum(secondary, valueFn)

    val primaryRemainder = round8(primaryQty - pos.abs) max 0d
    val primaryPercent = percent(primaryRemainder, primaryQty)

    val primaryWeightedVal = primaryVal * primaryPercent
    val merged = merge(primaryWeightedVal, primaryRemainder, secondaryVal, secondaryQty)
    round8(merged / leverage)
  }

  private def orderAmount(o: Order) = o.amount
  private def orderValue(instrument: Instrument): Order => Double =
    o => instrument.value(o.price.get) * o.amount

  private def percent(remainder: Double, total: Double): Double =
    if (total == 0d) 0d else remainder / total

  private def weight(value: Double, qty: Double): Double =
    if (qty == 0d) 0d else value / qty

  private def merge(valA: Double, qtyA: Double, valB: Double, qtyB: Double) = {
    val (hv, hq, lv, lq) =
      if (weight(valA, qtyA) > weight(valB, qtyB))
        (valA, qtyA, valB, qtyB)
      else (valB, qtyB, valA, qtyA)
    val rem = round8(lq - hq) max 0d
    val perc = percent(rem, lq)
    hv + perc * lv
  }

  private def sum(arr: Array[Order], fn: Order => Double): Double = {
    var sum: Double = 0
    cfor(0)(_ < arr.length, _ + 1) { i =>
      sum += fn(arr(i))
    }
    round8(sum)
  }

}
