package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.Order.{Buy, Fill, Sell}
import com.infixtrading.flashbot.models.core.{Account, Portfolio}

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

  // When you sell this instrument, what asset do you get back in exchange?
  def settledIn: String

  def markPrice(prices: PriceIndex): Double = prices(symbol)

  def PNL(amount: Double, entryPrice: Double, exitPrice: Double): Double = {
    if (security.get == base && settledIn == quote) amount * (exitPrice - entryPrice)
    else throw new RuntimeException(s"Can't calculate default PNL for $this")
  }

  def settle(exchange: String, fill: Fill, portfolio: Portfolio): Portfolio

  def canShort: Boolean

  override def toString: String = symbol
}

object Instrument {

  case class CurrencyPair(base: String, quote: String) extends Instrument {
    override def symbol = s"${base}_$quote"
    override def settledIn = quote
    override def security = Some(base)
    override def markPrice(prices: PriceIndex) = prices(this)
    override def canShort = false

    override def settle(exchange: String, fill: Fill, portfolio: Portfolio) = {
      def acc(str: String) = Account(exchange, str)
      fill.side match {
        /**
          * If we just bought some BTC using USD, then the fee was already subtracted
          * from the amount of available funds when determining the size of BTC filled.
          * Simply add the filled size to the existing BTC balance for base. For quote,
          * it's a little more complicated. We need to reconstruct the original amount
          * of quote funds (total cost) that was used for the order.
          *
          * total_cost * (1 - fee) = size * price
          */
        case Buy =>
          // Cost without accounting for fees. This is what we pay the maker.
          val rawCost = fill.size * fill.price

          // Total quote currency paid out to both maker and exchange
          val totalCost = rawCost / (1 - fill.fee)
          portfolio
            .updateAssetBalance(acc(base), _ + fill.size)
            .updateAssetBalance(acc(quote), _ - totalCost)

        /**
          * If we just sold a certain amount of BTC for USD, then the fee is subtracted
          * from the USD that is to be credited to our account balance.
          */
        case Sell =>
          portfolio
            .updateAssetBalance(acc(base), _ - fill.size)
            .updateAssetBalance(acc(quote), _ + fill.size * fill.price * (1 - fill.fee))
      }
    }
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
  }

  case class Index(symbol: String, base: String, quote: String) extends Instrument {
    override def security = None
    override def settledIn = {
      throw new NotImplementedError("Index does not implement `settledIn`")
    }
    override def markPrice(prices: PriceIndex) = {
      throw new NotImplementedError("Index does not implement `markPrice`")
    }
    override def settle(exchange: String, fill: Fill, portfolio: Portfolio) = {
      throw new RuntimeException("Indexes are not tradable")
    }
    override def canShort = false
  }

  trait Derivative extends Instrument {
    override def canShort = true
  }
  trait FuturesContract extends Derivative {
    override def settle(exchange: String, fill: Fill, portfolio: Portfolio) = {
      throw new NotImplementedError()
    }
  }
  trait OptionsContract extends Derivative

  implicit def instrument2Str(inst: Instrument): String = inst.symbol
}

