package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.{Account, Market}

import scala.collection.immutable.{Map, Set}

class InstrumentIndex(val instruments: Map[String, Set[Instrument]]) extends AnyVal {
  def apply(exchange: String, symbol: String): Instrument = apply(Market(exchange, symbol))
  def apply(market: Market): Instrument = get(market).get
  def get(exchange: String, symbol: String): Option[Instrument] =
    get(Market(exchange, symbol))
  def get(market: Market): Option[Instrument] =
    instruments(market.exchange).find(_.symbol == market.symbol)

  def forExchange(exchange: String): InstrumentIndex = instruments.filterKeys(_ == exchange)

  def accounts: Set[Account] = instruments.flatMap { case (ex, insts) =>
    insts.flatMap { inst => Set(Account(ex, inst.security.get), Account(ex, inst.settledIn)) }
  }.toSet
}

object InstrumentIndex {
  implicit def instrumentIndex(instruments: Map[String, Set[Instrument]]): InstrumentIndex
    = new InstrumentIndex(instruments)
}

