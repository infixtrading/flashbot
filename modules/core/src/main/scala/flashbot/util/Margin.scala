package flashbot.util

import flashbot.core.Instrument
import flashbot.core.Instrument.Derivative
import flashbot.models.{Order, OrderBook}
import spire.syntax.cfor._
import NumberUtils._
import Math.abs

object Margin {

  def calcOrderMargin(pos: Double, leverage: Double,
                      book: OrderBook, instrument: Derivative): Double = {

    val asksArray = book.asksArray
    val bidsArray = book.bidsArray

    val (primary, secondary) =
      if (pos >= 0) (asksArray, bidsArray)
      else (bidsArray, asksArray)

    val primaryQty = abs(sum(primary, orderAmount))
    val secondaryQty = abs(sum(secondary, orderAmount))

    val valueFn = orderValue(instrument)
    val primaryVal = abs(sum(primary, valueFn))
    val secondaryVal = abs(sum(secondary, valueFn))

    val primaryRemainder = round8(primaryQty - pos.abs) max 0d
    val primaryPercent = percent(primaryRemainder, primaryQty)

    val primaryWeightedVal = primaryVal * primaryPercent
    val merged = merge(primaryWeightedVal, primaryRemainder, secondaryVal, secondaryQty)

    def _calcOrderMargin(_pos: Double, _leverage: Double,
                        _book: OrderBook, _instrument: Derivative): Double = {

      val _asks = _book.asksArray.toSeq
      val _bids = _book.bidsArray.toSeq

      val (_primary, _secondary) =
        if (_pos >= 0) (_asks, _bids)
        else (_bids, _asks)

      def _sum(seq: Seq[Order], fn: Order => Double) = {
        val x = seq.map(fn).sum.abs
        if (seq.isEmpty) 0.0 else x
      }

      def _percent(remainder: Double, total: Double) =
        if (total == 0) 0.0 else remainder / total

      def weight(value: Double, qty: Double) =
        if (qty == 0) 0.0 else value / qty

      def _merge(valA: Double, qtyA: Double, valB: Double, qtyB: Double) = {
        val (hv, hq, lv, lq) =
          if (weight(valA, qtyA) > weight(valB, qtyB))
            (valA, qtyA, valB, qtyB)
          else (valB, qtyB, valA, qtyA)
        val rem = math.max(lq - hq, 0)
        val perc = _percent(rem, lq)
        hv + perc * lv
      }

      def _orderValue(order: Order) =
        _instrument.value(order.price.get).amount * order.amount

      val _primaryQty = _sum(_primary, _.amount)
      val _secondaryQty = _sum(_secondary, _.amount)

      val _primaryVal = _sum(_primary, _orderValue)
      val _secondaryVal = _sum(_secondary, _orderValue)

      val _primaryRemainder: Double = math.max(_primaryQty - _pos.abs, 0.0)
      val _primaryPercent: Double = _percent(_primaryRemainder, _primaryQty)

      val _primaryWeightedVal = _primaryVal * _primaryPercent
      val _merged = _merge(_primaryWeightedVal, _primaryRemainder, _secondaryVal, _secondaryQty)

      _merged / _leverage
    }

    val oldVal = _calcOrderMargin(pos, leverage, book, instrument)


    round8(merged / leverage)
  }

  private def orderAmount(o: Order) = o.amount
  private def orderValue(instrument: Instrument): Order => Double =
    o => instrument.valueDouble(o.price.get) * o.amount

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
