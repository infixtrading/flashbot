package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.LadderSpread.SpreadConfig

class LadderSpread(val config: SpreadConfig) {

  private var ladders = Map.empty[String, Ladder]

  /**
    * Builds a ladder from both legs.
    *
    * The asks are how much we can buy left and simultaneously sell right.
    * The bids are how much we can sell left and buy right.
    *
    * Quotes are denominated in the left quote asset.
    * Amounts are denominated in the left base asset.
    *
    * Example:
    *
    * Left leg: ETHUSD
    * Right right: ETH/USDT
    * ---------------------------------
    *         |     $1.5      |  1.0
    *         |     $1.1      |  3.3
    *         |     $0.2      |  4.0
    *         |     $-1.2     |  0.5
    *         |     $-1.5     |  1.1
    *         |     $-2.0     |  0.5      <-- Can buy .5 ETH using the ETHUSD instrument for
    * ---------------------------------       $2.0 less than we can sell .5 ETH using ETH/USDT
    *    1.0  |     $-2.2     |
    *    5.4  |     $-2.6     |
    *    3.1  |     $-3.4     |
    *    2.2  |     $-4.5     |
    *    1.0  |     $-5.0     |
    * ---------------------------------
    *
    * @param formula our spread formula which transforms the right price into one that can be
    *                subtracted from the left price to calculate a spread.
    */
  def ladder(formula: Double => Double): Ladder = {

    // Convert the right ladder quotes to the denominations of the left one.
    val converted = rightLadder.map {
      case (price, quantity) =>
        val newPrice = formula(price)
        val newQty = (quantity * price) / newPrice
        (newPrice, newQty)
    }

    // Intersect the two ladders
    leftLadder.intersect(converted)
  }

  def rightLadder: Ladder = ladders(config.right.instrument.symbol)
  def leftLadder: Ladder = ladders(config.left.instrument.symbol)
}

object LadderSpread {

  case class Leg(instrument: Instrument, quoting: Boolean, hedging: Boolean)

  case class SpreadConfig(legs: Seq[Leg]) {
    def left: Leg = legs.head
    def right: Leg = legs(1)
  }
}
