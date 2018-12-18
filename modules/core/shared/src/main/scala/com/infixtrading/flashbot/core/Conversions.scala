package com.infixtrading.flashbot.core

import java.util

import com.infixtrading.flashbot.models.core._

import scala.collection.JavaConverters._

trait Conversions {
  def findPricePath(baseKey: AssetKey, quoteKey: AssetKey)
                   (implicit prices: PriceIndex,
                    instruments: InstrumentIndex): Option[Seq[FixedPrice[Account]]]

//  def apply(source: AssetKey, target: AssetKey, approx: Boolean)
//           (implicit prices: PriceIndex,
//            instruments: InstrumentIndex): Option[FixedPrice[AssetKey]] =
//    if (prices.equiv(source.security, target.security) && source.exchange == target.exchange) {
//      Some(FixedPrice(1.0, (source, target)))
//    } else {
//      val path = prices.pricePathOpt(source, target, approx)
//      val ret = path map (_ reduce (_ compose _))
//      ret
//    }

//  def apply(source: AssetKey, target: AssetKey)
//           (implicit prices: PriceIndex,
//            instruments: InstrumentIndex): FixedPrice[AssetKey] =
//    apply(source, target, approx = false).orElse(apply(source, target, approx = true)).get

}
