package com.infixtrading.flashbot.exchanges
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import com.infixtrading.flashbot.core.Instrument.CurrencyPair
import com.infixtrading.flashbot.core.{Exchange, Instrument, OrderRequest}

import scala.concurrent.Future

class Bitfinex(implicit val system: ActorSystem,
               val mat: ActorMaterializer) extends Exchange {
  override def makerFee = .0000
  override def takerFee = .0000
  override def order(req: OrderRequest) = ???
  override def cancel(id: String, pair: Instrument) = ???


  override def baseAssetPrecision(pair: Instrument): Int = pair match {
    case CurrencyPair("eur", "usd") => 5
    case _ => 8
  }

  override def quoteAssetPrecision(pair: Instrument): Int = pair match {
    case CurrencyPair("xrp", "eur") => 5
    case CurrencyPair("xrp", "usd") => 5
    case CurrencyPair("eur", "usd") => 5
    case CurrencyPair(_, "usd") => 2
    case CurrencyPair(_, "eur") => 2
    case _ => 8
  }

  override def fetchPortfolio = Future.successful((Map.empty, Map.empty))
}
