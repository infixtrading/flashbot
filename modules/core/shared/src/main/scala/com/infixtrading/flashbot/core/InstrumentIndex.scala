package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.{Account, FixedPrice, Market}

import scala.collection.concurrent.TrieMap
import scala.collection.immutable.{Map, Set}

class InstrumentIndex(val byExchange: Map[String, Set[Instrument]]) {

  def apply(exchange: String, symbol: String): Instrument = apply(Market(exchange, symbol))
  def apply(market: Market): Instrument = get(market).get
  def get(exchange: String, symbol: String): Option[Instrument] =
    get(Market(exchange, symbol))
  def get(market: Market): Option[Instrument] =
    byExchange(market.exchange).find(_.symbol == market.symbol)


//  def pricePath(from: AssetKey, to: AssetKey, approx: Boolean): Seq[Market] =
//    pricePathOpt(from, to, approx).get
//  def pricePath(from: AssetKey, to: AssetKey): Seq[Market] =
//    pricePathOpt(from, to, approx = false).orElse(pricePathOpt(from, to, approx = true)).get
//  def pricePathOpt(from: AssetKey, to: AssetKey) = pricePathOpt(from, to, approx = false)


  //  def forExchange(exchange: String): InstrumentIndex = InstrumentIndex(
//    instruments.filterKeys(_ == exchange),
//    pricePathCache.remove()
//  )

  def accounts: Set[Account] = byExchange.flatMap { case (ex, insts) =>
    insts.flatMap { inst => Set(Account(ex, inst.security.get), Account(ex, inst.settledIn)) }
  }.toSet
}

