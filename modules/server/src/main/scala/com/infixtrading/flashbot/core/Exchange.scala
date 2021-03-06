package com.infixtrading.flashbot.core

import java.util.UUID.randomUUID

import com.typesafe.config.Config
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import com.infixtrading.flashbot.core.Instrument.CurrencyPair
import com.infixtrading.flashbot.engine.TradingSession
import com.infixtrading.flashbot.models.core.FixedSize.FixedSizeD
import com.infixtrading.flashbot.models.core.{FixedSize, Portfolio, Position}
import com.infixtrading.flashbot.models.core.Order.{Fill, Side}

import scala.collection.JavaConverters._
import scala.math.BigDecimal.RoundingMode.HALF_DOWN
import scala.concurrent.Future

abstract class Exchange {

  def makerFee: Double
  def takerFee: Double

  // API requests submitted to the exchange are fire-and-forget, hence the Unit return type
  def order(req: OrderRequest): Unit
  def cancel(id: String, pair: Instrument): Unit

  def baseAssetPrecision(pair: Instrument): Int
  def quoteAssetPrecision(pair: Instrument): Int
  def lotSize(pair: Instrument): Option[Double] = None

  def useFundsForMarketBuys: Boolean = false

  var tick: () => Unit = () => {
    throw new RuntimeException("The default tick function should never be called")
  }
  def setTickFn(fn: () => Unit): Unit = {
    tick = fn
  }

  private var fills = Seq.empty[Fill]
  def fill(f: Fill): Unit = fills :+= f

  private var events = Seq.empty[OrderEvent]
  def event(e: OrderEvent): Unit = events :+= e

  /**
    * A function that returns user data by the exchange in its current state for the given
    * trading session.
    */
  def collect(session: TradingSession,
              data: Option[MarketData[_]]): (Seq[Fill], Seq[OrderEvent]) = {
    val ret = (fills, events)
    fills = Seq.empty
    events = Seq.empty
    ret
  }

  def fetchPortfolio: Future[(Map[String, Double], Map[String, Position])]

  def genOrderId: String = randomUUID.toString

  def instruments: Future[Set[Instrument]] = Future.successful(Set.empty)

  def roundQuote(instrument: Instrument)(balance: Double): Double = BigDecimal(balance)
    .setScale(quoteAssetPrecision(instrument), HALF_DOWN).doubleValue()
  def roundBase(instrument: Instrument)(balance: Double): Double = BigDecimal(balance)
    .setScale(baseAssetPrecision(instrument), HALF_DOWN).doubleValue()

  def round(instrument: Instrument)(size: FixedSizeD): FixedSizeD =
    if (size.security == instrument.security.get)
      size.map(roundBase(instrument))
    else if (size.security == instrument.settledIn)
      size.map(roundQuote(instrument))
    else throw new RuntimeException(s"Can't round $size for instrument $instrument")

  private var jsonParams: Option[Json] = _
  def withParams(json: Option[Json]): Exchange = {
    jsonParams = json
    this
  }
}

sealed trait OrderRequest {
  val clientOid: String
  val side: Side
  val product: Instrument
}

final case class LimitOrderRequest(clientOid: String,
                                   side: Side,
                                   product: Instrument,
                                   size: Double,
                                   price: Double,
                                   postOnly: Boolean) extends OrderRequest

final case class MarketOrderRequest(clientOid: String,
                                    side: Side,
                                    product: Instrument,
                                    size: Option[Double],
                                    funds: Option[Double]) extends OrderRequest

