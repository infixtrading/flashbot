package com.infixtrading.flashbot.core

import scala.collection.immutable.{Map, Set}

/**
  * The data structure used to calculate positions, equity, PnL, order sizes, etc... This will
  * typically be populated from the last trade price or a candle.
  */
class PriceIndex(val prices: Map[Market, Double]) extends AnyVal {
  //  def convert(account: Account, value: Double, targetAsset: String): Double = ???

  //  def filterBase(fn: String => Boolean): PriceMap = prices.filterKeys(p => fn(p.product.base))
  //  def filterQuote(fn: String => Boolean): PriceMap = prices.filterKeys(p => fn(p.product.quote))

  def get(symbol: String): Option[Double] = {
    val matches = prices.filterKeys(_.symbol == symbol)
    if (matches.size == 1) {
      Some(matches.head._2)
    } else if (matches.size > 1) {
      throw new RuntimeException(s"Ambiguous symbol. Found more than one price for $symbol.")
    }
    None
  }

  def get(market: Market): Option[Double] = prices.get(market)

  def withPrice(market: Market, price: Double): Map[Market, Double] = prices + (market -> price)

  def apply(symbol: String): Double = get(symbol).get
  def apply(market: Market): Double = get(market).get

  def forExchange(exchange: String): PriceIndex = prices.filterKeys(_.exchange == exchange)

  // Hard coded for now.
  def pegs: Pegs = Pegs.default
}

object PriceIndex {
  implicit def priceMap(prices: Map[Market, Double]): PriceIndex = new PriceIndex(prices)

  def empty: PriceIndex = Map.empty[Market, Double]
}

