package flashbot.core

import java.util
import java.util.concurrent.ConcurrentHashMap

import flashbot.models.core.{Account, FixedPrice, Market}

import scala.collection.{concurrent, mutable}
import scala.language.postfixOps

/**
  * The data structure used to calculate positions, equity, PnL, order sizes, etc... This will
  * typically be populated from the last trade price or a candle.
  */
trait PriceIndex {
  /**
    * Calculate price using only the prices on the referenced exchanges if `strict`
    * is true, but use all available exchanges otherwise.
    */
  def calcPrice(source: AssetKey, target: AssetKey, strict: Boolean)
               (implicit instruments: InstrumentIndex, metrics: Metrics): Double

  def calcPrice(source: AssetKey, target: AssetKey)
               (implicit instruments: InstrumentIndex, metrics: Metrics): Double =
    calcPrice(source, target, strict = false)

  /**
    * Are these securities either equal or pegged to each other?
    */
  def equiv(a: String, b: String)(implicit metrics: Metrics): Boolean

  protected[flashbot] def setPrice(market: Market, price: Double)
                                  (implicit instruments: InstrumentIndex): Unit

  def get(symbol: String): Double
  def get(market: Market): Double

  def apply(symbol: String): Double = get(symbol)
  def apply(market: Market): Double = get(market)

  def getOpt(symbol: String): Option[Double]
  def getOpt(market: Market): Option[Double]

  def pegs: Pegs

  def getMarkets: Set[Market]
}


