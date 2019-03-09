package flashbot.core

import flashbot.models.core.Order._
import flashbot.models.core.{Account, FixedPrice, FixedSize, Portfolio}

trait Instrument {

  // Unique string id such as "eth_usdt" or "xbtusd"
  def symbol: String

  // The base asset/security of this instrument. I.e. what are we quoting?
  def base: String

  // What is the denomination of the price of this instrument?
  def quote: String

  // When you buy this instrument, what are you buying? This will usually be `base` in
  // the case of currency pairs, or `symbol` in the case of derivatives.
  // If None, it's not tradable. Such as an index.
  def security: Option[String]

  // When you sell this instrument, what asset do you build back in exchange?
  // Will be `None` for non-tradable instruments such as indexes.
  def settledIn: Option[String]

  def markPrice(prices: PriceIndex): Double = prices(symbol)

//  def PNL(amount: Double, entryPrice: Double, exitPrice: Double): Double = {
//    if (security.get == base && settledIn == quote) amount * (exitPrice - entryPrice)
//    else throw new RuntimeException(s"Can't calculate default PNL for $this")
//  }

//  def settle(exchange: String, fill: Fill, portfolio: Portfolio): Portfolio

  def canShort: Boolean

  override def toString: String = symbol

  /**
    * The value of one unit of this security/contract in terms of the settlement asset.
    */
  def valueDouble(price: Double): Double
  def value(price: Double): FixedSize[Double] = new FixedSize(valueDouble(price), settledIn.get)
}

object Instrument {

  case class CurrencyPair(base: String, quote: String) extends Instrument with Labelled {
    override def symbol = s"${base}_$quote"
    override def label = s"$base/$quote".toUpperCase
    override def settledIn = Some(quote)
    override def security = Some(base)
    override def markPrice(prices: PriceIndex) = prices(this)
    override def canShort = false

//    override def settle(exchange: String, fill: Fill, portfolio: Portfolio) = {
//      def acc(str: String) = Account(exchange, str)
//      fill.side match {
//        case Buy =>
//          // Cost without accounting for fees. This is what we pay the maker.
//          val rawCost = fill.size * fill.price
//
//          // Total quote currency paid out to both maker and exchange
//          val totalCost = rawCost / (1 - fill.fee)
//          portfolio
//            .updateAssetBalance(acc(base), _ + fill.size)
//            .updateAssetBalance(acc(quote), _ - totalCost)
//
//        case Sell =>
//          portfolio
//            .updateAssetBalance(acc(base), _ - fill.size)
//            .updateAssetBalance(acc(quote), _ + fill.size * fill.price * (1 - fill.fee))
//      }
//    }

    override def valueDouble(price: Double) = price
  }

  object CurrencyPair {
    implicit def apply(str: String): CurrencyPair = {
      var list = str.split("-")
      if (list.length == 1)
        list = str.split("_")
      CurrencyPair(list(0).toLowerCase, list(1).toLowerCase)
    }

    implicit class CurrencyPairOps(product: String) {
      def pair: CurrencyPair = CurrencyPair(product)
    }

    def parse(string: String): Option[CurrencyPair] = try {
      Some(CurrencyPair(string))
    } catch {
      case err: Throwable =>
        None
    }
  }

  case class Index(symbol: String, base: String, quote: String) extends Instrument {
    override def security = None
    override def settledIn = {
      throw new NotImplementedError("Index does not implement `settledIn`")
    }
    override def markPrice(prices: PriceIndex) = {
      throw new NotImplementedError("Index does not implement `markPrice`")
    }
//    override def settle(exchange: String, fill: Fill, portfolio: Portfolio) = {
//      throw new RuntimeException("Indexes are not tradable")
//    }
    override def canShort = false

    override def valueDouble(price: Double) = ???
  }

  trait Derivative extends Instrument {
    def pnl(size: Long, entryPrice: Double, exitPrice: Double): Double

    override def canShort = true
  }

  trait FuturesContract extends Derivative
  trait OptionsContract extends Derivative

  implicit def instrument2Str(inst: Instrument): String = inst.symbol
}

