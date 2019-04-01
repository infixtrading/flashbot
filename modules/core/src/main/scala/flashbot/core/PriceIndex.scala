package flashbot.core

import flashbot.models.Market
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
  def calcPrice[B: AssetKey, Q: AssetKey](source: B, target: Q, strict: Boolean)
               (implicit instruments: InstrumentIndex, metrics: Metrics): Double

  def calcPrice[B: AssetKey, Q: AssetKey](source: B, target: Q)
               (implicit instruments: InstrumentIndex, metrics: Metrics): Double =
    calcPrice(source, target, strict = false)

  /**
    * Are these securities either equal or pegged to each other?
    */
  def equiv(a: String, b: String)(implicit metrics: Metrics): Boolean

  protected[flashbot] def setPrice(market: Market, price: Double)
                                  (implicit instruments: InstrumentIndex): Unit

//  def get(symbol: String): Num
//  def get(market: Market): Num

  def apply(symbol: String): Double
  def apply(market: Market): Double

  // TODO: Rename these to `get`
  def getOpt(symbol: String): Option[Double]
  def getOpt(market: Market): Option[Double]

  def pegs: Pegs

  def getMarketsJava: java.util.Set[Market]
}


