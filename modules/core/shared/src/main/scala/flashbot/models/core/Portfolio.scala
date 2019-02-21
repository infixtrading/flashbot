package flashbot.models.core

import flashbot.core.Instrument.Derivative
import flashbot.core.{InstrumentIndex, PriceIndex}
import flashbot.models.core.FixedSize._
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

import scala.collection.immutable.Map
import scala.language.postfixOps

import scala.util.parsing.combinator.RegexParsers

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
                 (implicit prices: PriceIndex, instruments: InstrumentIndex): FixedSize[Double] = {
    val position = positions(market)
    instruments(market) match {
      case instrument: Derivative =>
        val pnlVal = instrument.pnl(position.size, position.entryPrice.get, prices(market))
        FixedSize(pnlVal, instrument.settledIn)
    }
  }

  /**
    * Positions are uninitialized because price data was unknown at the time that they were
    * created. This method is called whenever price data is updated, so this is where we try
    * to finally initialize them.
    */
  def initializePositions(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): Portfolio = {
    positions.filter(_._2.entryPrice.isEmpty).foldLeft(this) {
      case (memo, (market, position)) =>
        prices.get(market).map(price => instruments(market) match {
          case instrument: Derivative =>
            val account = Account(market.exchange, instrument.settledIn)
            val newPosition = position.copy(entryPrice = Some (price) )
            val temp = memo.setPosition (market, newPosition)
              // If there is no balance for the asset which this position is settled in, then infer
              // it to be this position's initial margin requirement.
            if (memo.assets.isDefinedAt(account)) temp
            else temp.withAssetBalance(account, newPosition.initialMargin(instrument))
        }).getOrElse(memo)

    }
  }

  /**
    * What is the value of our portfolio in terms of `targetAsset`?
    */
  def equity(targetAsset: String = "usd")
            (implicit prices: PriceIndex,
             instruments: InstrumentIndex): FixedSize[Double] = {
    import FixedSize.numericDouble._
    val PNLs = positions.keys.map(positionPNL(_) as targetAsset).sum
    val assetsEquity = balances.map(_ as targetAsset size).sum
    assetsEquity + PNLs
  }

  def position(market: Market): Option[Position] = positions.get(market)

  def setPositionSize(market: Market, size: Long)
                     (implicit instruments: InstrumentIndex,
                      prices: PriceIndex): Portfolio = {
    instruments(market) match {
      case instrument: Derivative =>
        val account = Account(market.exchange, instrument.symbol)
        val (newPosition, pnl) = positions(market).setSize(size, instrument, prices(market))
        setPosition(market, newPosition)
          .withAssetBalance(account, assets(account) + pnl)
    }
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
  // balances with the realized PNL that occurs from changing a position size.
  protected[flashbot] def setPosition(market: Market, position: Position): Portfolio =
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

  override def toString = {
    (assets.toSeq.map(a => Seq(a._1, a._2).mkString("=")) ++
        positions.toSeq.map(p => Seq(p._1, p._2).mkString("="))).sorted
      .mkString(",")
  }

}

object Portfolio extends RegexParsers {

  implicit val portfolioEn: Encoder[Portfolio] = deriveEncoder
  implicit val portfolioDe: Decoder[Portfolio] = deriveDecoder

  def empty: Portfolio = Portfolio(Map.empty, Map.empty)

  val key = raw"(.+)/(.+)".r
  val position = raw"(-?[0-9\.]+)(x[0-9\.]+)?(@[0-9\.]+)?".r

  object Optional {
    def unapply[T](a: T) = if (null == a) Some(None) else Some(Some(a))
  }

  /**
    * Comma separated list of balance or position expressions.
    *
    * bitmex/xbtusd=-10000x2@500,coinbase/btc=5.0,coinbase/usd=0
    * bitmex/xbtusd=-10000@500
    * bitmex/xbtusd=-10000x2,bitmex/xbtusd=-10000
    */
  def parse(expr: String)(implicit instruments: InstrumentIndex): Portfolio = {
    expr.split(",").foldLeft(empty) {
      case (portfolio, item) => item.split("=").toList match {
        case k@key(exchange, symbol) :: pos :: Nil =>
          (pos, instruments.get(exchange, symbol).isDefined) match {

            case (position(size, Optional(None), Optional(None)), false) =>
              portfolio.withAssetBalance(Account(exchange, symbol), size.toDouble)

            case (position(size, Optional(leverage), Optional(entry)), true) =>
              portfolio.setPosition(Market(exchange, symbol),
                Position(size.toLong,
                  leverage.map(_.toDouble).getOrElse(1.0),
                  entry.map(_.toDouble)))

            case _ => throw new RuntimeException(s"No such instrument: $k")
          }
      }
    }
  }
}

