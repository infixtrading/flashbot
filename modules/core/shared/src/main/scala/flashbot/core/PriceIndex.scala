package flashbot.core

import flashbot.core.PriceIndex._
import flashbot.models.core.{Account, FixedPrice, Market}

import scala.collection.{concurrent, mutable}
import scala.language.postfixOps

/**
  * The data structure used to calculate positions, equity, PnL, order sizes, etc... This will
  * typically be populated from the last trade price or a candle.
  */
class PriceIndex(private val priceMap: mutable.Map[Market, Double] = mutable.Map.empty,
                 private val pricePathCache: concurrent.Map[CacheKey, Seq[FixedPrice[Account]]] =
                    concurrent.TrieMap.empty[CacheKey, Seq[FixedPrice[Account]]])
                (implicit val conversions: Conversions) {

  private def pricePathOpt(from: AssetKey, to: AssetKey, approx: Boolean)
                          (implicit instruments: InstrumentIndex): Option[Seq[FixedPrice[Account]]] = {
    val mentionedExchanges = Seq(from.exchange, to.exchange).collect { case Some(ex) => ex }
    val filteredPrices =
      if (approx) markets
      else markets.filter(mentionedExchanges contains _.exchange)
    val exchanges = filteredPrices.map(_.exchange)
    val key =
      if (approx) CacheKey(from.security, to.security, exchanges)
      else CacheKey(from, to, exchanges)

    val pathOpt = pricePathCache.get(key)
      // If this is coming from the cache, then the structure of the price path is useful for us,
      // but the prices are probably outdated, so update them using the current price map.
      .map(_.map(fp => {
        val priceForward = instruments.findMarket(fp.base, fp.quote).flatMap(priceMap.get)
        val priceBackward = instruments.findMarket(fp.quote, fp.base)
          .flatMap(priceMap.get).map(1.0 / _)

        fp.copy(price = priceForward.orElse(priceBackward).get)
      }))
      // If not coming from the cache, the prices must be correct already.
      .orElse(conversions.findPricePath(key.from, key.to)(this, instruments))

    // Update the cache
    if (pathOpt.isDefined) {
      pricePathCache.update(key, pathOpt.get)
    }
    pathOpt
  }

  /**
    * Convert using only the prices on this exchange, unless `approx` is true. In that
    * case, we fallback to other exchanges in case we can't find a conversion on this one.
    */
  def convert(source: AssetKey, target: AssetKey, approx: Boolean)
             (implicit instruments: InstrumentIndex): Option[FixedPrice[AssetKey]] = {
    val default = FixedPrice(1.0, (source, target))
    if (equiv(source.security, target.security) && (source.exchange == target.exchange)) {
      Some(default)
    } else {
      implicit val pi: PriceIndex = this
      val pathOpt = pricePathOpt(source, target, approx)

      pathOpt map (path =>
        if (path isEmpty) default
        else path reduce (_ compose _) map AssetKey.apply)
    }
  }

  /**
    * Try to convert using only the prices on this exchange. Then, fallback to all exchanges.
    */
  def convert(source: AssetKey, target: AssetKey)
             (implicit instruments: InstrumentIndex): Option[FixedPrice[AssetKey]] =
    convert(source, target, approx = false).orElse(convert(source, target, approx = true))

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
//      .map(m => ((instruments(m).security.build, instruments(m).settledIn), prices(m)))
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
//        if (lastNode.build.exchange.build == node.exchange.build) {
//          // First find a forward price.
//          val forwardPrice: Option[Double] = instruments.instruments(node.exchange.build)
//            .find(i => i.security.build == lastNode.build.symbol && i.settledIn == node.symbol)
//            .map(i => prices(Market(node.exchange.build, i.symbol)))
//
//          // Otherwise look for backward price.
//          val finalPrice = forwardPrice.orElse(instruments.instruments(node.exchange.build)
//            .find(i => i.security.build == node.symbol && i.settledIn == lastNode.build.symbol)
//            .map(i => 1d / prices(Market(node.exchange.build, i.symbol))))
//
//          if (finalPrice.isDefined) {
//            price = price.map(_ * finalPrice.build)
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

