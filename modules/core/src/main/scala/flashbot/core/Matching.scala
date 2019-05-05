package flashbot.core

import flashbot.models.OrderBook.OrderBookSide
import flashbot.models.{Ladder, LadderSide, OrderBook}
import flashbot.util.NumberUtils

trait Matching extends Any {

  val matchPrices: Array[Double] = Array[Double](200)
  val matchQtys: Array[Double] = Array[Double](200)

  var matchCount: Int = 0
  var matchTotalQty: Double = 0d

  // Optional out var. Order books will use it. Ladders won't.
  val matchOrderIds: Option[Array[String]] = None

  def foreachMatch(fn: (Double, Double) => Unit): Unit = {
    var i = 0
    while(matchPrices(i) != -1 && matchQtys(i) != -1) {
      fn(matchPrices(i), matchQtys(i))
      i += 1
    }
  }

  /**
    * Matches up until the price/size limits.
    * Removes the filled liquidity from the ladder.
    * Terminates out arrays with -1.
    *
    * @return the remaining unmatched qty.
    */
  def matchMutable(quoteSide: QuoteSide,
                   approxPriceLimit: Double,
                   approxSize: Double): Double

  /**
    * Like `matchMutable`, but doesn't remove the liquidity from the ladder itself.
    * Terminates the arrays with -1.
    *
    * @return the remaining unmatched qty.
    */
  def matchSilent(quoteSide: QuoteSide,
                  approxPriceLimit: Double,
                  approxSize: Double): Double

  /**
    * Like `matchSilent`, but returns average match instead of individual matches.
    *
    * @return (total size matched, unrounded avg price)
    */
  def matchSilentAvg(quoteSide: QuoteSide,
                     approxPriceLimit: Double,
                     approxSize: Double): (Double, Double)
}
