package com.infixtrading.flashbot.core

import java.util

import com.infixtrading.flashbot.models.core._

import scala.collection.JavaConverters._

trait Conversions {
  def findPricePath(baseKey: AssetKey, quoteKey: AssetKey)
                   (implicit prices: PriceIndex,
                    instruments: InstrumentIndex): Option[Seq[FixedPrice]]

  /**
    * Convert using only the prices on this exchange, unless `approx` is true. In that
    * case, we fallback to other exchanges in case we can't find a conversion on this one.
    */
  def apply(source: AssetKey, target: AssetKey, approx: Boolean)
           (implicit prices: PriceIndex, instruments: InstrumentIndex): Option[FixedPrice] =
    if (prices.equiv(source.symbol, target.symbol) && source.exchange == target.exchange) {
      Some(FixedPrice(1.0, (source, target)))
    } else {
      val path = prices.pricePathOpt(source, target, approx)
      path map (_ reduce (_ compose _))
    }

  /**
    * Try to convert using only the prices on this exchange. Then, fallback to all exchanges.
    * And if the fallback fails, crash.
    */
  def apply(source: AssetKey, target: AssetKey)
           (implicit prices: PriceIndex, instruments: InstrumentIndex): FixedPrice =
    apply(source, target, approx = false).orElse(apply(source, target, approx = true)).get

}

object Conversions {
  abstract class Edge {
    def fp: FixedPrice
  }
  class Equiv(val fp: FixedPrice) extends Edge {
    override def toString = "Equiv"
  }
  class PriceEdge(val fp: FixedPrice) extends Edge {
    def flip = new PriceEdge(fp.flip)
    override def toString = fp.toString
  }
}
