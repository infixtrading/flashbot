package flashbot.core
/**
  * Keeps track of asset balances and positions across all exchanges.
  * Calculates equity and PnL.
  */
class Portfolio(private val assets: debox.Map[Account, Double],
                private val positions: debox.Map[Market, Position],
                private val orders: debox.Map[Market, OrderBook],
                protected[flashbot] var lastUpdate: Option[PortfolioDelta])
    extends HasUpdateEvent[Portfolio, PortfolioDelta] {

  private var recordingBuffer: Option[mutable.Buffer[PortfolioDelta]] = None

  protected[flashbot] def record(fn: Portfolio => Portfolio): Unit = {
    if (recordingBuffer.isDefined) {
      throw new RuntimeException("Portfolio already recording.")
    }

    recordingBuffer = Some(mutable.ArrayBuffer.empty)
    fn(this)
    withLastUpdate(BatchPortfolioUpdate(recordingBuffer.get))
    recordingBuffer = None
  }

  override protected def withLastUpdate(d: PortfolioDelta): Portfolio = {
    lastUpdate = Some(d)
    this
  }

  override protected def step(delta: PortfolioDelta): Portfolio = {
    delta match {
      case BalanceUpdated(account, None) =>
        assets.remove(account)

      case BalanceUpdated(account, Some(balance)) =>
        assets.put(account, balance)

      case PositionUpdated(market, None) =>
        positions.remove(market)

      case PositionUpdated(market, Some(position)) =>
        positions.put(market, position)

      case OrdersUpdated(market, bookDelta) =>
        orders.get(market).update(bookDelta)

      case BatchPortfolioUpdate(deltas) =>
        deltas.foreach(delta => this.update(delta))
    }

    if (recordingBuffer.isDefined) recordingBuffer.get.append(delta)
    else withLastUpdate(delta)

    this
  }

  def getBalance(account: Account): Double = assets.getOrDefault(account, `0`)

  def getBalanceAs(account: Account): FixedSize = getBalance(account).of(account)

  def withBalance(account: Account, balance: Double): Portfolio =
    step(BalanceUpdated(account, Some(balance)))

  def updateAssetBalance(account: Account, fn: Double => Double): Portfolio =
    withBalance(account, fn(getBalance(account)))

  /**
    * The initial margin/cost of posting an order PLUS fees. This returns the size of
    * a specific asset that should would be placed on hold or used towards the order
    * margin by the exchange.
    */
  def getOrderCost(market: Market, size: Double, price: Double, liquidity: Liquidity)
                  (implicit instruments: InstrumentIndex,
                   exchangesParams: Map[String, ExchangeParams]): Double = {
    assert(size != `0`, "Order size cannot be 0")

    val fee = liquidity match {
      case Maker => exchangesParams(market.exchange).makerFee
      case Taker => exchangesParams(market.exchange).takerFee
    }

    instruments(market) match {
      case _: Derivative =>
        // For derivatives, the order cost is the difference between the order margin
        // with the order and without.
        (addOrder(None, market, size, price).getOrderMargin(market) -
          getOrderMargin(market)) * (`1` + fee)

      case _ =>
        // For non-derivatives, there is no margin.
        // Order cost for buys = size * price * (1 + fee).
        if (size > `0`)
          size * price * (`1` + fee)

        // And order cost for asks is just the size of the order:
        // In order to sell something, you must have it first.
        // That is your only cost.
        else
          size.abs
    }
  }

  /**
    * The minimum equity that must be retained to keep all orders open on the given
    * market. If the current position is short
    */
  def getOrderMargin(market: Market)
                    (implicit instruments: InstrumentIndex): Double = {
    instruments(market) match {
      case derivative: Derivative =>
        val position = positions.get(market)
        Margin.calcOrderMargin(position.size, position.leverage,
          orders(market), derivative)
    }
  }

  /**
    * Entry value of positions / leverage + unrealized pnl.
    */
  def getPositionMargin(market: Market)
                       (implicit instruments: InstrumentIndex,
                        prices: PriceIndex,
                        metrics: Metrics): Double = {
    val instrument = instruments(market).asInstanceOf[Derivative]
    val position = positions(market)
    position.initialMargin(instrument) + getPositionPnl(market)
  }

  /**
    * Margin Balance = Wallet Balance + Unrealised PNL
    * Available Balance = Margin Balance - Order Margin - Position Margin
    * ...  = Wallet Balance - Order Margin - Position Margin (without including PNL)
    */
  def getAvailableBalance(account: Account)
                         (implicit instruments: InstrumentIndex,
                          prices: PriceIndex,
                          metrics: Metrics): Double = {
    var sum = getBalance(account)
    positions.foreachKey { market =>
      if (market.settlementAccount == account) {
        sum += (getPositionPnl(market) -
          getOrderMargin(market) -
          getPositionMargin(market))
      }
    }
//    val pnl = markets.map(getPositionPnl(_)).foldLeft(`0`)(_ + _)
//    val oMargin = markets.map(getOrderMargin(_)).foldLeft(`0`)(_ + _)
//    val pMargin = markets.map(getPositionMargin(_)).foldLeft(`0`)(_ + _)
    round8(sum)
  }

  def addOrder(id: Option[String], market: Market, size: Double, price: Double): Portfolio = {
    var book = orders(market)
    if (book == null) book = OrderBook()
    val side = if (size > `0`) Order.Buy else Order.Sell

    step(OrdersUpdated(market,
      OrderBook.Open(id.getOrElse(book.genID), price, size.abs, side)))

    this
  }

  def removeOrder(market: Market, id: String): Portfolio = {
    val book = orders.get(market)
    if (book != null && book.orders.isDefinedAt(id)) {
      this.step(OrdersUpdated(market, OrderBook.Done(id)))
    }
    this
  }

  def fillOrder(market: Market, fill: Fill)
               (implicit instruments: InstrumentIndex,
                exchangeParams: java.util.Map[String, ExchangeParams]): Portfolio = {
    val size = if (fill.side == Buy) fill.size else -fill.size

    // Derivatives will update realized pnl on fills.
    if (positions.contains(market)) {

      // If this fill is opening a position, update the settlement account with the
      // realized pnl from the fee.
      val instrument = instruments(market).asInstanceOf[Derivative]
      val position = positions(market)
      val (newPosition, realizedPnl) =
        position.updateSize(position.size + size, instrument, fill.price)
      val feeCost = instrument.value(fill.price) * fill.size * fill.fee

      updateAssetBalance(market.settlementAccount, _ + (realizedPnl - feeCost))
        .withPosition(market, newPosition)

    } else {
      val cost = getOrderCost(market, size, fill.price, fill.liquidity)
      // Other instruments update balances and account for fees.
      fill.side match {
        case Buy =>
          updateAssetBalance(market.settlementAccount, _ - cost)
            .updateAssetBalance(market.securityAccount, _ + fill.size)
        case Sell =>
          updateAssetBalance(market.settlementAccount,
              _ + fill.size * fill.price * (`1` - fill.fee))
            .updateAssetBalance(market.securityAccount, _ - cost)
      }
    }
  }

  def getPositionPnl(market: Market)
                    (implicit prices: PriceIndex,
                     instruments: InstrumentIndex,
                     metrics: Metrics): Double = {
    val position = positions(market)
    instruments(market) match {
      case instrument: Derivative =>
        val price = prices.calcPrice(market.baseAccount, market.quoteAccount)
        instrument.pnl(position.size, position.entryPrice, price)
    }
  }

  def getLeverage(market: Market): Num = {
    val position = positions.get(market)
    if (position != null) position.leverage
    else `1`
  }

  /**
    * Positions are uninitialized because price data was unknown at the time that they were
    * created. This method is called whenever price data is updated, so this is where we try
    * to finally initialize them.
    */
  def initializePositions(implicit prices: PriceIndex,
                          instruments: InstrumentIndex,
                          metrics: Metrics): Portfolio = {
    positions.entrySet().stream()
        .filter(_.getValue.entryPrice.isEmpty)
        .forEach { entry =>
          val (market, position) = (entry.getKey, entry.getValue)
          val price = prices.calcPrice(market.baseAccount, market.quoteAccount)
          if (!price.isNaN)
            instruments(market) match {
              case instrument: Derivative =>
                val account = Account(market.exchange, instrument.settledIn.get)
                val newPosition = position.copy(entryPrice = Some(price) )
                this.withPosition(market, newPosition)

                // If there is no balance for the asset which this position is settled in, then infer
                // it to be this position's initial margin requirement.
                val marg = newPosition.initialMargin(instrument)
                if (!this.assets.containsKey(account))
                  this.withBalance(account, marg)
            }
        }
    this
  }

  /**
    * Whether the positions are initialized, and prices exist such that all accounts
    * can be converted to the given target asset.
    */
  def isInitialized(targetAsset: String = "usd")
                   (implicit prices: PriceIndex,
                    instruments: InstrumentIndex,
                    metrics: Metrics): Boolean = {
    val positionsReady = positions.entrySet()
      .stream().allMatch(_.getValue.entryPrice.nonEmpty)
    positionsReady &&
      assets.keySet().stream().allMatch(acc =>
        prices.calcPrice(acc, targetAsset).isValid)
  }

  /**
    * What is the value of our portfolio in terms of `targetAsset`?
    */
  def getEquity(targetAsset: String = "usd")
               (implicit prices: PriceIndex,
                instruments: InstrumentIndex,
                metrics: Metrics): Num = {
    var equitySum = `0`
    positions.keySet().stream().forEach { key =>
      equitySum += getPositionPnl(key)
        .convert(key.settlementAccount, targetAsset)
    }

    assets.entrySet().stream().forEach { entry =>
      val (account, balance) = (entry.getKey, entry.getValue)
      equitySum += balance * prices.calcPrice(account, targetAsset)
    }

    equitySum
  }

//  def setPositionSize(market: Market, size: Long)
//                     (implicit instruments: InstrumentIndex,
//                      prices: PriceIndex): Portfolio = {
//    instruments(market) match {
//      case instrument: Derivative =>
//        val account = Account(market.exchange, instrument.symbol)
//        val (newPosition, pnl) = positions(market).setSize(size, instrument, prices(market))
//        setPosition(market, newPosition)
//          .withAssetBalance(account, balance(account).qty + pnl)
//    }
//  }
//
//  def closePosition(market: Market)
//                   (implicit instruments: InstrumentIndex,
//                    prices: PriceIndex): Portfolio =
//    setPositionSize(market, 0)
//
//  def closePositions(markets: Seq[Market])
//                    (implicit instruments: InstrumentIndex,
//                     prices: PriceIndex): Portfolio =
//    markets.foldLeft(this)(_.closePosition(_))
//
//  def closePositions(implicit instruments: InstrumentIndex,
//                     prices: PriceIndex): Portfolio =
//    closePositions(positions.keys.toSeq)

  // This is unsafe because it lets you set a new position without updating account
  // balances with the realized PNL that occurs from changing a position size.
  protected[flashbot] def withPosition(market: Market, position: Position): Portfolio =
    step(PositionUpdated(market, Some(position)))

//  def merge(portfolio: Portfolio): Portfolio = copy(
//    assets = assets ++ portfolio.assets,
//    positions = positions ++ portfolio.positions
//  )

  /**
    * Returns this portfolio without any elements that exist in `other`.
    */
//  def diff(other: Portfolio): Portfolio = copy(
//    assets = assets.filterNot { case (acc, value) => other.assets.get(acc).contains(value) },
//    positions = positions.filterNot {
//      case (market, value) => other.positions.get(market).contains(value) }
//  )

  def withoutExchange(name: String): Portfolio = {
    assets.keySet().stream().filter(_.exchange == name).forEach { acc =>
      step(BalanceUpdated(acc, None))
    }
    positions.keySet().stream().filter(_.exchange == name).forEach { ex =>
      step(PositionUpdated(ex, None))
    }
    this
  }

  /**
    * Splits each account's total equity/buying power evenly among all given markets.
    */
  //  def isolatedBuyingPower(markets: Seq[Market],
  //                          priceMap: PriceMap,
  //                          equityDenomination: String): Map[Market, Double] = {
  //    // First close all positions.
  //    val closed = this.closePositions(priceMap)
  //
  //    // Calculate total equity per account.
  //    val accountEquities: Map[Account, Double] =
  //      closed.positions.mapValues(_.value(equityDenomination, priceMap))
  //
  //    // Distribute between all markets of account.
  //    accountEquities.flatMap { case (account, buyingPower) =>
  //      val accountMarkets = markets.filter(_.instrument.settledIn == account.security)
  //      accountMarkets.map(inst => inst -> buyingPower / accountMarkets.size)
  //    }
  //  }

  override def toString = {
    (assets.asScala.toSeq.map(a => Seq(a._1, a._2).mkString("=")) ++
        positions.asScala.toSeq.map(p => Seq(p._1, p._2).mkString("="))).sorted
      .mkString(",")
  }

}
