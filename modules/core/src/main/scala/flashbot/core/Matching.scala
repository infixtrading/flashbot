package flashbot.core

import spire.syntax.cfor._

trait Matching extends Any {

  // Implementers must supply references to shared out vars.
  def priceMatches: Array[Double]
  def qtyMatches: Array[Double]

  def foreachMatch(fn: (Double, Double) => Unit): Unit = {
    var i = 0
    while(priceMatches(i) != -1 && qtyMatches(i) != -1) {
      fn(priceMatches(i), qtyMatches(i))
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
                   approxSize: Double,
                   matchPricesOut: Array[Double] = priceMatches,
                   matchQtysOut: Array[Double] = qtyMatches): Double

  /**
    * Like `matchMutable`, but doesn't remove the liquidity from the ladder itself.
    * Terminates the arrays with -1.
    *
    * @return the remaining unmatched qty.
    */
  def matchSilent(quoteSide: QuoteSide,
                  approxPriceLimit: Double,
                  approxSize: Double,
                  matchPricesOut: Array[Double] = priceMatches,
                  matchQtysOut: Array[Double] = qtyMatches): Double

  /**
    * Like `matchSilent`, but returns average match instead of individual matches.
    *
    * @return (total size matched, unrounded avg price)
    */
  def matchSilentAvg(quoteSide: QuoteSide,
                     approxPriceLimit: Double,
                     approxSize: Double): (Double, Double)
}
