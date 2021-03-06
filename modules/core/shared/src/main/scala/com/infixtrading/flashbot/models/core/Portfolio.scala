package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.{InstrumentIndex, PriceIndex}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import FixedSize._

import scala.collection.immutable.Map

/**
  * Keeps track of asset balances and positions across all exchanges. Calculates equity and PnL.
  */
case class Portfolio(assets: Map[Account, Double],
                     positions: Map[Market, Position]) {

  def balance(account: Account): Balance = Balance(account, assets.getOrElse(account, 0.0))
  def withAssetBalance(account: Account, balance: Double): Portfolio =
    copy(assets = assets + (account -> balance))
  def updateAssetBalance(account: Account, fn: Double => Double): Portfolio =
    withAssetBalance(account, fn(balance(account).qty))

  def balances: Set[Balance] = assets map { case (acc, qty) => Balance(acc, qty) } toSet

  def positionPNL(market: Market)
                 (implicit prices: PriceIndex, instruments: InstrumentIndex): FixedSizeD = {
    val position = positions(market)
    val instrument = instruments(market)
    val pnlVal = instrument.PNL(position.size, position.entryPrice, prices(market))
    FixedSize(pnlVal, instrument.settledIn)
  }

  /**
    * What is the value of our portfolio in terms of `targetAsset`?
    */
  def equity(targetAsset: String = "usd")
            (implicit prices: PriceIndex,
             instruments: InstrumentIndex): FixedSizeD = {
    import FixedSize.dNumeric._
    val assetsEquity = balances.map(_ as targetAsset size).sum
    val PNLs = positions.keys.map(positionPNL(_) as targetAsset).sum
    assetsEquity + PNLs
  }

  def position(market: Market): Option[Position] = positions.get(market)

  def setPositionSize(market: Market, size: Long)
                     (implicit instruments: InstrumentIndex,
                      prices: PriceIndex): Portfolio = {
    val instrument = instruments(market)
    val account = Account(market.exchange, instrument.symbol)
    val (newPosition, pnl) = positions(market).setSize(size, instrument, prices(market))
    unsafeSetPosition(market, newPosition)
      .withAssetBalance(account, assets(account) + pnl)
  }

  def closePosition(market: Market)
                   (implicit instruments: InstrumentIndex,
                    prices: PriceIndex): Portfolio =
    setPositionSize(market, 0)

  def closePositions(markets: Seq[Market])
                    (implicit instruments: InstrumentIndex,
                     prices: PriceIndex): Portfolio =
    markets.foldLeft(this)(_.closePosition(_))

  def closePositions(implicit instruments: InstrumentIndex,
                     prices: PriceIndex): Portfolio =
    closePositions(positions.keys.toSeq)

  // This is unsafe because it lets you set a new position without updating account
  // balances with PNL.
  def unsafeSetPosition(market: Market, position: Position): Portfolio =
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
      case (market, value) => positions.get(market).contains(value) }
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

}

object Portfolio {

  implicit val portfolioEn: Encoder[Portfolio] = deriveEncoder
  implicit val portfolioDe: Decoder[Portfolio] = deriveDecoder

  def empty: Portfolio = Portfolio(Map.empty, Map.empty)
}

