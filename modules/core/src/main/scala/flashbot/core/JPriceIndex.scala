package flashbot.core

import java.util
import java.util.Collections

import flashbot.util.MapUtil._

class JPriceIndex(val conversions: Conversions) extends PriceIndex {

  /**
    * Long living indexes
    */

  // Raw prices per market.
  private val priceMap = new java.util.HashMap[Market, FixedPrice[Account, Account]]()

  // Index of (base -> quote) accounts to market prices.
  private val priceAccountMap = hashMap2d[Account, FixedPrice[Account, Account]]

  // Cache of (base -> quote) accounts to markets.
//  private val marketIndex = hashMap2d[Account, Market]

  // Index to support the lookup of markets by their symbol.
  private val symbolsToMarkets = new util.HashMap[String, java.util.Set[Market]]()

  override def getMarketsJava = priceMap.keySet()


  /**
    * Short lived indexes. Reset whenever the set of known markets changes.
    */

  // Price path entries and cached calculations.
  private var pricePathCacheStrict = hashMap2d[Any, PricePathEntry]
  private var pricePathCacheApprox = hashMap2d[String, PricePathEntry]
  private var marketDependencies = hashMap2d[Account, java.util.Set[PricePathEntry]]

//  private def lookupMarket(base: Account, quote: Account)
//                          (implicit instruments: InstrumentIndex) =
//    getOrCompute(marketIndex, base, quote, instruments.findMarket(base, quote).orNull)

  private def computePath[B: AssetKey, Q: AssetKey]
      (from: B, to: Q, strict: Boolean)
      (implicit instruments: InstrumentIndex,
       metrics: Metrics): PricePathEntry = {

    val baseOps = implicitly[AssetKey[B]]
    val quoteOps = implicitly[AssetKey[Q]]

    val newInstruments = if (strict) {
      val fromInstruments = baseOps.exchangeOpt(from)
        .map(e => instruments.byExchange.filterKeys(_ == e))
        .getOrElse(Map.empty)
      val toInstruments = quoteOps.exchangeOpt(to)
        .map(e => instruments.byExchange.filterKeys(_ == e))
        .getOrElse(Map.empty)
      new InstrumentIndex(fromInstruments ++ toInstruments)
    } else instruments

    val pathArr = conversions.findPricePath(from, to)(
      baseOps, quoteOps, this, newInstruments, metrics)

    if (pathArr == null)
      return null

    var idx = 0
    while (idx < pathArr.length) {
      val fp = lookupPriceInstance(pathArr(idx))
      pathArr(idx) = fp
      idx += 1
    }

    val fromAcc =
      if (pathArr.nonEmpty)
        Some(Account(pathArr.head.base.exchange, baseOps.security(from)))
      else None

    val toAcc =
      if (pathArr.nonEmpty)
        Some(Account(pathArr.last.base.exchange, quoteOps.security(to)))
      else None

    val entry = new PricePathEntry(fromAcc, toAcc, pathArr)

    // Update the market -> entry index, so that, for every market, we have a list of
    // references to price paths that depend on that market's price.
    idx = 0
    while (idx < entry.path.length) {
      val fp = entry.path(idx)
      val set = getOrCompute(marketDependencies, fp.base, fp.quote,
        Collections.newSetFromMap[PricePathEntry](new java.util.IdentityHashMap()))
      set.add(entry)
      idx += 1
    }

    entry
  }

//  private def fetchPriceInstance(base: Account, quote: Account)
//                                (implicit instruments: InstrumentIndex): FixedPrice[AccountAsset] = {
//    lazy val market: Market = {
//      if (base.exchange != quote.exchange)
//        throw new RuntimeException(s"Base ($base) and quote ($quote) accounts must have the same exchange.")
//      lookupMarket(base, quote)
//    }
//    getOrCompute(priceAccountMap, base, quote,
//      getOrCompute(priceMap, market,
//        new FixedPrice[AccountAsset](Double.NaN, (base, quote))))
//  }

  private def lookupPriceInstance(priceEdge: FixedPrice[Account, Account])
                                 (implicit instruments: InstrumentIndex): FixedPrice[Account, Account] =
    if (priceEdge.flipped)
      priceAccountMap.get(priceEdge.quote).get(priceEdge.base)
    else priceAccountMap.get(priceEdge.base).get(priceEdge.quote)


  override def calcPrice[B: AssetKey, Q: AssetKey]
      (source: B, target: Q, strict: Boolean)
      (implicit instruments: InstrumentIndex,
       metrics: Metrics): Double = this.synchronized {
    val timer = metrics.startTimer("convert_calc")
    val ret = {
      // Retrieve or build the path.
      var isNewPath = false
      val pricePath =
        if (strict) getOrCompute(
          pricePathCacheStrict, source, target, {
            isNewPath = true
            computePath(source, target, strict)
          })
        else getOrCompute(
          pricePathCacheApprox, source.security, target.security, {
            isNewPath = true
            computePath(source.security, target.security, strict)
          })


      if (pricePath != null) {
        // Update the reverse path
        if (isNewPath) {
          if (strict) getOrCompute(pricePathCacheStrict, target, source, pricePath)
          else getOrCompute(
            pricePathCacheApprox, target.security, source.security, pricePath)
        }

        pricePath.calcPriceOf(source, target)

      } else NaN
    }
    timer.close()
    ret
  }

  def get(symbol: String): Num = {
    val matches = symbolsToMarkets.get(symbol)
    if (matches != null) {
      if (matches.size() == 1) {
        val market = matches.iterator().next()
        return get(market)
      } else if (matches.size() > 1) {
        throw new RuntimeException(s"Ambiguous symbol. Found more than one price for $symbol.")
      }
    }
    NaN
  }

  def get(market: Market): Num = {
    val fp = priceMap.get(market)
    if (fp != null) fp.price
    else NaN
  }

  protected[flashbot] def setPrice(market: Market, price: Num)
                                  (implicit instruments: InstrumentIndex): Unit = this.synchronized {
    // Update the price map.
    var isNewMarket = false
    val fp = getOrCompute(priceMap, market, {
      isNewMarket = true
      new FixedPrice[AccountAsset](price, (market.baseAccount, market.quoteAccount))
    })

    // If this doesn't introduce a new market to the system, update the price and also set the
    // corresponding PricePathEntries as dirty.
    if (!isNewMarket) {
      fp.setPrice(price)
      val subMap = marketDependencies.get(fp.base)
      if (subMap != null) {
        val set = subMap.get(fp.quote)
        if (set != null) {
          val it = set.iterator()
          while (it.hasNext) {
            it.next().isDirty = true
          }
        }
      }
    } else {
      // If it does introduce a new market, we have to clear our path caches.
      pricePathCacheStrict = hashMap2d[AssetKey, PricePathEntry]
      pricePathCacheApprox = hashMap2d[String, PricePathEntry]
      marketDependencies = hashMap2d[Account, java.util.Set[PricePathEntry]]

      // Also update the market index.
      getOrCompute(priceAccountMap, fp.base.account, fp.quote.account, fp)

      // And the symbols-to-markets index
      val markets = getOrCompute(symbolsToMarkets, market.symbol, new util.HashSet[Market]())
      markets.add(market)
    }
  }

//  def apply(symbol: String): Double = get(symbol).get
//  def apply(market: Market): Double = get(market).get

//  def forExchange(exchange: String): PriceIndex = forExchanges(exchange)
//  def forExchanges(exchanges: String*): PriceIndex =
//    new PriceIndex(priceMap.filter(exchanges contains _._1.exchange))

//  def exchanges: collection.Set[String] = markets.map(_.exchange)

  // Hard coded for now.
  override val pegs: Pegs = Pegs.default

  def equiv(a: String, b: String)(implicit metrics: Metrics): Boolean =
    (a == b) || pegs.of(a).contains(b)

  override def toString = priceMap.toString

//  private var pathsApprox = new java.util.HashMap[String, java.util.HashMap[String, PricePathEntry]]()
//  private var pathsStrict = new java.util.HashMap[AssetKey, java.util.HashMap[AssetKey, PricePathEntry]]()

//  private def fetchCacheKey(from: String, to: String): CacheKey = {
//    val keyMap = getOrCompute(keysApproxFrom, from, new util.HashMap[String, CacheKey]())
//
//    val cacheKey = getOrCompute(keyMap, to, CacheKey(from, to))
//
//    var cacheKey = keyMap.get(to)
//    if (cacheKey == null) {
//      cacheKey = CacheKey(from, to)
//      keyMap.put(to, cacheKey)
//    }
//
//    cacheKey
//  }
//
//  private def fetchCacheKey(from: AssetKey, to: AssetKey): CacheKey = {
//    if (from.isSecurity && to.isSecurity) {
//      throw new RuntimeException("Strict price conversions must include at least one Account key.")
//    }
//
//    var keyMap = keysStrictFrom.get(from)
//    if (keyMap == null) {
//      keyMap = new util.HashMap[AssetKey, CacheKey]()
//      keysStrictFrom.put(from, keyMap)
//    }
//
//    var cacheKey = keyMap.get(to)
//    if (cacheKey == null) {
//      cacheKey = CacheKey(from, to)
//      keyMap.put(to, cacheKey)
//    }
//
//    cacheKey
//  }



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

  /**
    * The result of running the [[Conversions.findPricePath]] method. Contains an array of mutable
    * [[FixedPrice]] objects which always have a corresponding market. Should be marked dirty when
    * the price of a market which is used in this path is updated.
    */
  class PricePathEntry(val from: Option[Account],
                       val to: Option[Account],

                       // Every item of this path needs to be a reference to a FixedPrice instance
                       // in `accountsPriceMap`.
                       val path: Array[FixedPrice[Account, Account]],

                       // Mutable vars
                       var cachedPrice: Double = 1.0,
                       var isDirty: Boolean = true)
                      (implicit metrics: Metrics){
    recalc()

    private def isEmpty: Boolean = path.isEmpty

    private def recalc(): Unit = {
      if (isDirty) {
        cachedPrice = `1`
        if (!isEmpty) {
          var idx = 0
          var acc = from.get
          val reverse = path.length > 1 &&
            ((equiv(from.get.security, path.last.base.security) ||
              equiv(from.get.security, path.last.quote.security)) &&
                (equiv(to.get.security, path.head.base.security) ||
                 equiv(to.get.security, path.head.quote.security)))
          while (idx < path.length) {
            val fp = path(if (reverse) path.length - idx - 1 else idx)
            if (equiv(acc.security, fp.base.security)) {
              acc = fp.quote.account
              cachedPrice *= fp.price
            } else if (equiv(acc.security, fp.quote.security)) {
              acc = fp.base.account
              cachedPrice *= (`1` / fp.price)
            } else throw new RuntimeException(s"Invalid price path for accounts $from to $to: ${path.toSeq}")
            idx += 1
          }
        }
        isDirty = false
      }
    }

    def calcPriceOf(fromKey: AssetKey, toKey: AssetKey): Num = {
      recalc()
      if (isEmpty) cachedPrice
      else if (equiv(from.get.security, fromKey.security) && equiv(to.get.security, toKey.security)) cachedPrice
      else if (equiv(from.get.security, toKey.security) && equiv(to.get.security, fromKey.security)) `1` / cachedPrice
      else throw new RuntimeException(s"Invalid keys in priceFor: ($fromKey, $toKey). " +
        s"Expected keys equivalent to ($from, $to)")
    }
  }

  override def getOpt(symbol: String): Option[Num] = {
    val ret: Num = get(symbol)
    if (ret.isNaN) None
    else Some(ret)
  }

  override def getOpt(market: Market): Option[Num] = {
    val ret: Num = get(market)
    if (ret.isNaN) None
    else Some(ret)
  }
}
