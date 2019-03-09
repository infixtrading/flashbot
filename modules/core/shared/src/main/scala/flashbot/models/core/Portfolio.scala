package flashbot.models.core

import flashbot.core.Instrument.Derivative
import flashbot.core._
import flashbot.models.core.FixedSize._
import flashbot.models.core.Order.{Buy, Fill, Liquidity, Maker, Sell, Taker}
import flashbot.util.Margin
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

import scala.collection.immutable.Map
import scala.language.postfixOps

/**
  * Keeps track of asset balances and positions across all exchanges. Calculates equity and PnL.
  */
case class Portfolio(assets: Map[Account, Double],
                     positions: Map[Market, Position],
                     orders: Map[Market, OrderBook]) {
  import FixedSize.numericDouble._

  def balance(account: Account): Balance = Balance(account, assets.getOrElse(account, 0.0))
  def withAssetBalance(account: Account, balance: Double): Portfolio =
    copy(assets = assets + (account -> balance))
  def updateAssetBalance(account: Account, fn: Double => Double): Portfolio =
    withAssetBalance(account, fn(balance(account).qty))

  def balances: Set[Balance] = assets map { case (acc, qty) => Balance(acc, qty) } toSet

  /**
    * The initial margin/cost of posting an order PLUS fees. This returns the size of
    * a specific asset that should would be placed on hold or used towards the order
    * margin by the exchange.
    */
  def orderCost(market: Market, size: Double, price: Double, liquidity: Liquidity)
               (implicit instruments: InstrumentIndex,
                exchangesParams: Map[String, ExchangeParams]): FixedSize[Double] = {
    assert(size != 0, "Order size cannot be 0")
    val fee = liquidity match {
      case Maker => exchangesParams(market.exchange).makerFee
      case Taker => exchangesParams(market.exchange).takerFee
    }
    instruments(market) match {
      case _: Derivative =>
        // For derivatives, the order cost is the difference between the order margin
        // with the order and without.
        (withOrder(None, market, size, price).orderMargin(market) -
          orderMargin(market)) * (1 + fee)

      case _ =>
        // For non-derivatives, there is no margin.
        // Order cost for buys = size * price * (1 + fee).
        if (size > 0)
          (size * price * (1 + fee)).of(market.settlementAccount)

        // And order cost for asks is just the size of the order:
        // In order to sell something, you must have it first.
        // That is your only cost.
        else
          size.abs.of(market.securityAccount)
    }
  }

  /**
    * The minimum equity that must be retained to keep all orders open on the given
    * market. If the current position is short
    */
  def orderMargin(market: Market)
                 (implicit instruments: InstrumentIndex): FixedSize[Double] = {
    instruments(market) match {
      case derivative: Derivative =>
        val position = positions(market)
        Margin.calcOrderMargin(position.size,
          position.leverage, orders(market), derivative)
    }
  }

  /**
    * Entry value of positions / leverage + unrealized pnl.
    */
  def positionMargin(market: Market)
                    (implicit instruments: InstrumentIndex,
                     prices: PriceIndex,
                     metrics: Metrics): FixedSize[Double] = {
    val instrument = instruments(market).asInstanceOf[Derivative]
    val position = positions(market)
    position.initialMargin(instrument).of(market.settlementAccount) + positionPNL(market)
  }

  /**
    * Margin Balance = Wallet Balance + Unrealised PNL
    * Available Balance = Margin Balance - Order Margin - Position Margin
    */
  def availableBalance(account: Account)
                      (implicit instruments: InstrumentIndex,
                       prices: PriceIndex,
                       metrics: Metrics): FixedSize[Double] = {
    val walletBalance = balance(account).size
    val markets = positions.filter(_._1.settlementAccount == account).keys
    val pnl = markets.map(positionPNL(_)).foldLeft(0d.of(account))(_ + _)
    val oMargin = markets.map(orderMargin(_)).foldLeft(0d.of(account))(_ + _)
    val pMargin = markets.map(positionMargin(_)).foldLeft(0d.of(account))(_ + _)
    walletBalance + pnl - oMargin - pMargin
  }

  def withOrder(id: Option[String], market: Market,
                size: Double, price: Double): Portfolio = {
    val book = orders.getOrElse(market, OrderBook())
    val side = if (size > 0) Order.Buy else Order.Sell
    val order = new Order(id.getOrElse(book.genID), side, size.abs, Some(price))
    copy(orders = orders + (market -> book.open(order)))
  }

  def withoutOrder(market: Market, id: String): Portfolio = {
    val order = orders.get(market).flatMap(_.orders.get(id))
    if (order.isEmpty) this
    else copy(orders = orders + (market -> orders(market).done(id)))
  }

  def fillOrder(market: Market, fill: Fill)
               (implicit instruments: InstrumentIndex,
                exchangeParams: Map[String, ExchangeParams]): Portfolio = {
    val size = if (fill.side == Buy) fill.size else -fill.size

    // Derivatives will update realized pnl on fills.
    if (positions.isDefinedAt(market)) {

      // If this fill is opening a position, update the settlement account with the
      // realized pnl from the fee.
      val instrument = instruments(market).asInstanceOf[Derivative]
      val position = positions(market)
      val (newPosition, realizedPnl) =
        position.setSize(position.size + size.toLong, instrument, fill.price)
      val feeCost = instrument.valueDouble(fill.price) * fill.size * fill.fee
      updateAssetBalance(market.settlementAccount, _ + (realizedPnl - feeCost.amount))
        .copy(positions = positions + (market -> newPosition))

    } else {
      val cost = orderCost(market, size, fill.price, fill.liquidity)
      // Other instruments update balances and account for fees.
      fill.side match {
        case Buy =>
          updateAssetBalance(market.settlementAccount, _ - cost.amount)
            .updateAssetBalance(market.securityAccount, _ + fill.size)
        case Sell =>
          updateAssetBalance(market.settlementAccount,
              _ + fill.size * fill.price * (1 - fill.fee))
            .updateAssetBalance(market.securityAccount, _ - cost.amount)
      }
    }
  }

  def positionPNL(market: Market)
                 (implicit prices: PriceIndex,
                  instruments: InstrumentIndex,
                  metrics: Metrics): FixedSize[Double] = {
    val position = positions(market)
    instruments(market) match {
      case instrument: Derivative =>
        val entryPrice = position.entryPrice.get
        val price = prices.calcPrice(market.baseAccount, market.quoteAccount)
        val pnlVal = instrument.pnl(position.size, entryPrice, price)
        FixedSize(pnlVal, instrument.settledIn.get)
    }
  }

  def leverage(market: Market): Double = positions.get(market).map(_.leverage).getOrElse(1)

  /**
    * Positions are uninitialized because price data was unknown at the time that they were
    * created. This method is called whenever price data is updated, so this is where we try
    * to finally initialize them.
    */
  def initializePositions(implicit prices: PriceIndex,
                          instruments: InstrumentIndex,
                          metrics: Metrics): Portfolio = {
    positions.filter(_._2.entryPrice.isEmpty).toSeq.foldLeft(this) {
      case (memo, (market, position)) =>
        val price = prices.calcPrice(market.baseAccount, market.quoteAccount)
        if (java.lang.Double.isNaN(price)) memo
        else instruments(market) match {
          case instrument: Derivative =>
            val account = Account(market.exchange, instrument.settledIn.get)
            val newPosition = position.copy(entryPrice = Some(price) )
            val temp = memo.withPosition (market, newPosition)
            // If there is no balance for the asset which this position is settled in, then infer
            // it to be this position's initial margin requirement.
            val marg = newPosition.initialMargin(instrument)
            if (memo.assets.isDefinedAt(account)) temp
            else temp.withAssetBalance(account, marg)
        }

    }
  }

  /**
    * Whether the positions are initialized, and prices exist such that all accounts
    * can be converted to the given target asset.
    */
  def isReady(targetAsset: String = "usd")
             (implicit prices: PriceIndex,
              instruments: InstrumentIndex,
              metrics: Metrics): Boolean = {
    positions.forall(_._2.entryPrice.nonEmpty) &&
      assets.keySet.forall(x => !java.lang.Double.isNaN(prices.calcPrice(x, targetAsset)))
  }

  /**
    * What is the value of our portfolio in terms of `targetAsset`?
    */
  def equityDouble(targetAsset: String = "usd")
                  (implicit prices: PriceIndex,
                   instruments: InstrumentIndex,
                   metrics: Metrics): Double = {
    val PNLs = positions.keys.map(k => {
      positionPNL(k).asDouble(targetAsset)
    }).sum
    val assetsEquity = balances.map(_ asDouble targetAsset).sum
    assetsEquity + PNLs
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
    copy(positions = positions + (market -> position))

  def merge(portfolio: Portfolio): Portfolio = copy(
    assets = assets ++ portfolio.assets,
    positions = positions ++ portfolio.positions
  )

  /**
    * Returns this portfolio without any elements that exist in `other`.
    */
  def diff(other: Portfolio): Portfolio = copy(
    assets = assets.filterNot { case (acc, value) => other.assets.get(acc).contains(value) },
    positions = positions.filterNot {
      case (market, value) => other.positions.get(market).contains(value) }
  )

  def withoutExchange(name: String): Portfolio = copy(
    assets = assets.filterKeys(_.exchange != name),
    positions = positions.filterKeys(_.exchange != name)
  )

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
    (assets.toSeq.map(a => Seq(a._1, a._2).mkString("=")) ++
        positions.toSeq.map(p => Seq(p._1, p._2).mkString("="))).sorted
      .mkString(",")
  }

}

object Portfolio {

  implicit val portfolioEn: Encoder[Portfolio] = deriveEncoder
  implicit val portfolioDe: Decoder[Portfolio] = deriveDecoder

  def empty: Portfolio = Portfolio(Map.empty, Map.empty, Map.empty)
}

