package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.Instrument.CurrencyPair
import com.infixtrading.flashbot.models.core.{FixedPrice, Market}

import scala.collection.mutable
import scala.collection
import scala.collection.concurrent
import PriceIndex._

/**
  * The data structure used to calculate positions, equity, PnL, order sizes, etc... This will
  * typically be populated from the last trade price or a candle.
  */
class PriceIndex(private val priceMap: mutable.Map[Market, Double] = mutable.Map.empty,
                 private val pricePathCache: concurrent.Map[CacheKey, Seq[FixedPrice]] =
                    concurrent.TrieMap.empty[CacheKey, Seq[FixedPrice]])
                (implicit val conversions: Conversions) {

  def pricePathOpt(from: AssetKey, to: AssetKey, approx: Boolean)
                  (implicit instruments: InstrumentIndex): Option[Seq[FixedPrice]] = {
    val mentionedExchanges = Seq(from.exchange, to.exchange).collect { case Some(ex) => ex }
    val filteredPrices =
      if (approx) markets
      else markets.filter(mentionedExchanges contains _.exchange)
    val exchanges = filteredPrices.map(_.exchange)
    val key = CacheKey(from, to, exchanges)
    val pathOpt = pricePathCache.get(key).orElse(
      conversions.findPricePath(from, to)(this, instruments))
    if (pathOpt.isDefined) {
      pricePathCache.update(key, pathOpt.get)
    }
    pathOpt
  }

  //  def convert(account: Account, value: Double, targetAsset: String): Double = ???

  //  def filterBase(fn: String => Boolean): PriceMap = prices.filterKeys(p => fn(p.product.base))
  //  def filterQuote(fn: String => Boolean): PriceMap = prices.filterKeys(p => fn(p.product.quote))

//  private

//  def markets = priceMap.keySet

  def get(symbol: String): Option[Double] = {
    val matches = markets.filter(_.symbol == symbol)
    if (matches.size == 1) {
      Some(priceMap(matches.head))
    } else if (matches.size > 1) {
      throw new RuntimeException(s"Ambiguous symbol. Found more than one price for $symbol.")
    }
    None
  }

  def get(market: Market): Option[Double] = priceMap.get(market)

  protected[flashbot] def setPrice(market: Market, price: Double): Unit =
    priceMap.put(market, price)

  def apply(symbol: String): Double = get(symbol).get
  def apply(market: Market): Double = get(market).get

//  def forExchange(exchange: String): PriceIndex = forExchanges(exchange)
//  def forExchanges(exchanges: String*): PriceIndex =
//    new PriceIndex(priceMap.filter(exchanges contains _._1.exchange))

  def markets: collection.Set[Market] = priceMap.keySet
  def exchanges: collection.Set[String] = markets.map(_.exchange)

  // Hard coded for now.
  def pegs: Pegs = Pegs.default

  /**
    * Are these securities either equal or pegged to each other?
    */
  def equiv(a: String, b: String): Boolean = (a == b) || pegs.of(a).contains(b)

  override def toString = priceMap.toString

  /**
    * Returns the composite price of a chain of prices.
    *
    * Example: `compose([bitmex:xbtusd, binance:btc/usdt, binance:ven/btc])`
    *   returns the price of the xbtusd/ven synthetic market.
    */
//  def compose(markets: Seq[Market])(implicit instruments: InstrumentIndex): Double = {
//    markets
//      .map(m => ((instruments(m).security.get, instruments(m).settledIn), prices(m)))
//      .reduce { x =>
////        case (((prevBase: String, prevQuote: String), prevPrice: Double),
////              ((curBase: String, curQuote: String), curPrice: Double)) =>
////          (prevBase, prevQuote, curBase, curQuote) match {
////            case (pb, pq, cb, cq) if pb == cq =>
////              ((cb, pq), prevPrice * curPrice)
////          }
//        ???
//      }._2

    // Iterate over the nodes in the solution to compute the final base/quote price.
//    var lastNode: Option[Node] = None
//    var price: Option[Double] = Some(1)
//    solution.slice(1, solution.size - 1).foreach { node =>
//      if (lastNode.isDefined) {
//        if (lastNode.get.exchange.get == node.exchange.get) {
//          // First find a forward price.
//          val forwardPrice: Option[Double] = instruments.instruments(node.exchange.get)
//            .find(i => i.security.get == lastNode.get.symbol && i.settledIn == node.symbol)
//            .map(i => prices(Market(node.exchange.get, i.symbol)))
//
//          // Otherwise look for backward price.
//          val finalPrice = forwardPrice.orElse(instruments.instruments(node.exchange.get)
//            .find(i => i.security.get == node.symbol && i.settledIn == lastNode.get.symbol)
//            .map(i => 1d / prices(Market(node.exchange.get, i.symbol))))
//
//          if (finalPrice.isDefined) {
//            price = price.map(_ * finalPrice.get)
//          }
//        }
//      } else {
//        price = Some(1)
//      }
//      lastNode = Some(node)
//    }
//  }
}

object PriceIndex {
//  implicit def priceMap(prices: Map[Market, Double]): PriceIndex = new PriceIndex(prices)
  case class CacheKey(from: AssetKey, to: AssetKey, exchanges: collection.Set[String])

  def empty(implicit conversions: Conversions): PriceIndex =
    new PriceIndex(mutable.Map.empty[Market, Double])
}

