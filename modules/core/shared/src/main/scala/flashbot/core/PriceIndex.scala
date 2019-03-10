package flashbot.core

import java.util
import java.util.concurrent.ConcurrentHashMap

import flashbot.core.Num._
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
               (implicit instruments: InstrumentIndex, metrics: Metrics): Num

  def calcPrice(source: AssetKey, target: AssetKey)
               (implicit instruments: InstrumentIndex, metrics: Metrics): Num =
    calcPrice(source, target, strict = false)

  /**
    * Are these securities either equal or pegged to each other?
    */
  def equiv(a: String, b: String)(implicit metrics: Metrics): Boolean

  protected[flashbot] def setPrice(market: Market, price: Num)
                                  (implicit instruments: InstrumentIndex): Unit

  def get(symbol: String): Num
  def get(market: Market): Num

  def apply(symbol: String): Num = get(symbol)
  def apply(market: Market): Num = get(market)

  def getOpt(symbol: String): Option[Num]
  def getOpt(market: Market): Option[Num]

  def pegs: Pegs

  def getMarkets: Set[Market]
}


