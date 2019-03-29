package flashbot.core

import flashbot.models.core.{Account, Market}

import scala.collection.immutable.{Map, Set}

class InstrumentIndex(val byExchange: Map[String, Set[Instrument]]) {

  private lazy val byMarket = byExchange.flatMap {
    case (exchange, instruments) =>
      instruments.map(i => Market(exchange, i.symbol) -> i)
  }

  private lazy val byAccounts = byMarket.map {
    case (market, instrument) =>
      (market.baseAccount(this), market.quoteAccount(this)) -> instrument
  }

  def apply(exchange: String, symbol: String): Instrument = apply(Market(exchange, symbol))
  def apply(market: Market): Instrument = byMarket(market)

  def get(exchange: String, symbol: String): Option[Instrument] = get(Market(exchange, symbol))
  def get(market: Market): Option[Instrument] = byMarket.get(market)

//  def findMarket(base: Account, quote: Account): Option[Market] = {
//    if (base.exchange == quote.exchange) {
//      byExchange(base.exchange).find(i =>
//          (i.base == base.security) && (i.quote == quote.security))
//        .map(i => Market(base.exchange, i.symbol))
//    } else None
//  }

  def filterMarkets(fn: Market => Boolean): InstrumentIndex =
    new InstrumentIndex(byExchange.map {
      case (ex, insts) => ex -> insts.filter(inst => fn(Market(ex, inst.symbol)))
    }.filter(_._2.nonEmpty))

  def assetAccounts: Set[Account] = byExchange.flatMap { case (ex, insts) =>
    insts.flatMap { inst => Set(Account(ex, inst.base), Account(ex, inst.quote)) }
  }.toSet
}

