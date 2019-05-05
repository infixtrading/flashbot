package flashbot.models

import flashbot.core.DeltaFmt.HasUpdateEvent
import flashbot.core.Instrument.Derivative
import flashbot.core._
import flashbot.core.FixedSize._
import flashbot.models.Order.{Buy, Liquidity, Maker, Sell, Taker}
import flashbot.util.Margin
import flashbot.util.NumberUtils._
import flashbot.util.json.CommonEncoders._
import flashbot.core.AssetKey.implicits._
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

import scala.collection.immutable.Map
import scala.collection.mutable
import scala.language.postfixOps

/**
  * Keeps track of asset balances and positions across all exchanges.
  * Calculates equity and PnL.
  */
class Portfolio(private val assets: debox.Map[Account, Double],
                private val positions: debox.Map[Market, Position],
                private val orders: debox.Map[Market, OrderBook],
                protected[flashbot] val lastUpdate: MutableOpt[PortfolioDelta] = MutableOpt.from(None))
    extends HasUpdateEvent[Portfolio, PortfolioDelta] {

  private var recordingBuffer: MutableOpt[mutable.Buffer[PortfolioDelta]] = MutableOpt.from(None)

  protected[flashbot] def record(fn: Portfolio => Portfolio): Unit = {
    if (recordingBuffer.nonEmpty) {
      throw new RuntimeException("Portfolio already recording.")
    }

    recordingBuffer.set(mutable.ArrayBuffer.empty)
    fn(this)
    lastUpdate.set(BatchPortfolioUpdate(recordingBuffer.get))
    recordingBuffer.clear()
  }

  override protected def _step(delta: PortfolioDelta): Portfolio = {
    delta match {
      case BalanceUpdated(account, None) =>
        assets.remove(account)

      case BalanceUpdated(account, Some(balance)) =>
        assets(account) = balance

      case PositionUpdated(market, None) =>
        positions.remove(market)

      case PositionUpdated(market, Some(position)) =>
        positions(market) = position

      case OrdersUpdated(market, bookDelta) =>
        orders.get(market).get.update(bookDelta)

      case BatchPortfolioUpdate(deltas) =>
        deltas.foreach(delta => this.update(delta))
    }

    if (recordingBuffer.nonEmpty) recordingBuffer.get.append(delta)
    else lastUpdate.set(delta)

    this
  }

  def getPosition(market: Market): Position = positions(market)

  def getBalance(account: Account): Double = assets.get(account).getOrElse(0d)
  def getBalanceSize(account: Account): FixedSize = getBalance(account).of(account)

  def withBalance(account: Account, balance: Double): Portfolio =
    _step(BalanceUpdated(account, Some(balance)))

  def updateAssetBalance(account: Account, fn: Double => Double): Portfolio =
    withBalance(account, fn(getBalance(account)))

  private var lastCostAccount: Account = _

  /**
    * The initial margin/cost of posting an order PLUS fees. This returns the size of
    * a specific quote asset that would be placed on hold or used towards the order
    * margin by the exchange.
    */
  def getOrderCost(market: Market, size: Double, price: Double, liquidity: Liquidity)
                  (implicit instruments: InstrumentIndex,
                   exchangesParams: java.util.Map[String, ExchangeParams]): Double = {
    assert(size != 0, "Order size cannot be 0")

    val fee = liquidity match {
      case Maker => exchangesParams.get(market.exchange).makerFee
      case Taker => exchangesParams.get(market.exchange).takerFee
    }

    instruments(market) match {
      case inst: Derivative =>
        lastCostAccount = market.settlementAccount
        // For derivatives, the order cost is the difference between the order margin
        // with the order and without.
        (addOrder(None, market, size, price).getOrderMargin(market) -
          getOrderMargin(market)) * (1.0 + fee)

      case _ =>
        // For non-derivatives, there is no margin.
        // Order cost for buys = size * price * (1 + fee).
        if (size > 0) {
          lastCostAccount = market.quoteAccount
          size * price * (1.0 + fee)
        }

        // And order cost for asks is just the size of the order:
        // In order to sell something, you must have it first.
        // That is your only cost.
        else {
          lastCostAccount = market.baseAccount
          size.abs
        }
    }
  }

  def getOrderCostSize(market: Market, size: Double, price: Double, liquidity: Liquidity)
                      (implicit instruments: InstrumentIndex,
                       exchangesParams: java.util.Map[String, ExchangeParams]): FixedSize = {
    val cost = getOrderCost(market, size, price, liquidity)
    FixedSize(cost, lastCostAccount.security)
  }

  /**
    * The minimum equity that must be retained to keep all orders open on the given
    * market. If the current position is short
    */
  def getOrderMargin(market: Market)
                    (implicit instruments: InstrumentIndex): Double = {
    instruments(market) match {
      case derivative: Derivative =>
        val position = positions.get(market).get
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

  def addOrder(id: Option[String], market: Market, size: Double, price: Double)
              (implicit instrumentIndex: InstrumentIndex): Portfolio = {
    val instr = instrumentIndex(market)
    var book = orders(market)
    if (book == null) book = OrderBook(instr.tickSize)
    val side = if (size > 0) Order.Buy else Order.Sell

    _step(OrdersUpdated(market,
      OrderBook.Open(id.getOrElse(book.genID), price, size.abs, side)))

    this
  }

  def removeOrder(market: Market, id: String): Portfolio = {
    val book = orders.get(market).get
    if (book != null && book.orders.containsKey(id)) {
      this._step(OrdersUpdated(market, OrderBook.Done(id)))
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
      val feeCost = instrument.valueDouble(fill.price) * fill.size * fill.fee

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
              _ + fill.size * fill.price * (1.0 - fill.fee))
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

  def getLeverage(market: Market): Double = {
    val position = positions.get(market).get
    if (position != null) position.leverage
    else 1.0
  }

  /**
    * Positions are uninitialized because price data was unknown at the time that they were
    * created. This method is called whenever price data is updated, so this is where we try
    * to finally initialize them.
    */
  def initializePositions(implicit prices: PriceIndex,
                          instruments: InstrumentIndex,
                          metrics: Metrics): Portfolio = {
    positions.foreach { (market, position) =>
      if (!position.isInitialized) {
        val price = prices.calcPrice(market.baseAccount, market.quoteAccount)
        if (!java.lang.Double.isNaN(price))
          instruments(market) match {
            case instrument: Derivative =>
              val account = Account(market.exchange, instrument.settledIn.get)
              position.entryPrice = price
              this.withPosition(market, position)

              // If there is no balance for the asset which this position is settled in, then infer
              // it to be this position's initial margin requirement.
              val marg = position.initialMargin(instrument)
              if (!assets.contains(account))
                this.withBalance(account, marg)
          }
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
                    metrics: Metrics): Boolean =
    positions.vals.forall(_.isInitialized) &&
      assets.keys.forall(acc =>
        !java.lang.Double.isNaN(prices.calcPrice[Account, Symbol](acc, Symbol(targetAsset))))

  /**
    * What is the value of our portfolio in terms of `targetAsset`?
    */
  def getEquity(targetAsset: String = "usd")
               (implicit prices: PriceIndex,
                instruments: InstrumentIndex,
                metrics: Metrics): Double = {
    var equitySum = 0.0
    val ta: Symbol = Symbol(targetAsset)
    positions.foreachKey(key =>
      equitySum += getPositionPnl(key).of(key.settlementAccount).as(ta).amount)

    assets.foreach { (account, balance) =>
      equitySum += balance * prices.calcPrice(account, ta)
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
    _step(PositionUpdated(market, Some(position)))

  def merge(portfolio: Portfolio): Portfolio = {
    portfolio.assets.foreach((acc, value) => {
      assets += (acc -> value)
    })
    portfolio
  }

  /**
    * Returns this portfolio without any elements that exist in `other`.
    */
//  def diff(other: Portfolio): Portfolio = copy(
//    assets = assets.filterNot { case (acc, value) => other.assets.get(acc).contains(value) },
//    positions = positions.filterNot {
//      case (market, value) => other.positions.get(market).contains(value) }
//  )

  def withoutExchange(name: String): Portfolio = {
    assets.foreachKey { acc =>
      if (acc.exchange == name)
        _step(BalanceUpdated(acc, None))
    }
    positions.foreachKey { ex =>
      if (ex.exchange == name)
        _step(PositionUpdated(ex, None))
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

  override def toString: String = {
    (assets.iterator().toSeq.map(a => Seq(a._1, a._2).mkString("=")) ++
        positions.iterator().toSeq.map(p => Seq(p._1, p._2).mkString("="))).sorted
      .mkString(",")
  }

}

object Portfolio {

  implicit val portfolioEn: Encoder[Portfolio] =
    Encoder.forProduct4[Portfolio,
      debox.Map[Account, Double], debox.Map[Market, Position],
      debox.Map[Market, OrderBook], MutableOpt[PortfolioDelta]](
        "assets", "positions", "orders", "lastUpdate")(p => (p.assets, p.positions, p.orders, p.lastUpdate))

  implicit val portfolioDe: Decoder[Portfolio] =
    Decoder.forProduct4[Portfolio,
      debox.Map[Account, Double], debox.Map[Market, Position],
      debox.Map[Market, OrderBook], MutableOpt[PortfolioDelta]](
        "assets", "positions", "orders", "lastUpdate")(new Portfolio(_, _, _, _))

  def empty: Portfolio = new Portfolio(debox.Map.empty, debox.Map.empty, debox.Map.empty)
}

