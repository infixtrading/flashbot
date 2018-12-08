package com.infixtrading.flashbot

import java.time.Instant

import com.infixtrading.flashbot.core.Order.Side
import com.infixtrading.flashbot.util.time._

import scala.concurrent.duration.Duration

import io.circe._

package object core {

  case class TimeRange(from: Long, to: Long = Long.MaxValue)
  object TimeRange {
    def build(now: Instant, from: String, to: String): TimeRange = {
      val fromT = parseTime(now, from)
      val toT = parseTime(now, to)
      (fromT, toT) match {
        case (Right(inst), Left(dur)) => TimeRange(
          inst.toEpochMilli * 1000,
          inst.plusMillis(dur.toMillis).toEpochMilli * 1000)
        case (Left(dur), Right(inst)) => TimeRange(
          inst.minusMillis(dur.toMillis).toEpochMilli * 1000,
          inst.toEpochMilli * 1000)
      }
    }
  }

  def parseTime(now: Instant, str: String): Either[Duration, Instant] = {
    if (str == "now") {
      Right(now)
    } else {
      Left(parseDuration(str))
    }
  }

  sealed trait PairRole
  case object Base extends PairRole
  case object Quote extends PairRole

  case class Trade(id: String, micros: Long, price: Double, size: Double, side: Side) extends Timestamped with Priced

  case class Quote(bidPrice: Double,
                   bidAmount: Double,
                   askPrice: Double,
                   askAmount: Double) {
    def reverse: Quote = Quote(
      bidPrice = askPrice,
      bidAmount = askAmount,
      askPrice = bidPrice,
      askAmount = bidAmount
    )
  }

  case class Ticker(micros: Long,
                    bestBidPrice: Double,
                    bestBidQuantity: Double,
                    bestAskPrice: Double,
                    bestAskQuantity: Double,
                    lastTradePrice: Double,
                    lastTradeId: Long) extends Timestamped with Priced {
    def price = lastTradePrice
  }

  case class CurrencyConfig(name: Option[String],
                            alias: Option[String])


  case class BotConfig(strategy: String,
                       mode: String,
                       params: Json,
                       initial_assets: Map[String, Double],
                       initial_positions: Map[String, Position])

  trait Timestamped {
    def micros: Long
  }
  object Timestamped {
    val ordering: Ordering[Timestamped] = Ordering.by(_.micros)
  }

  trait Priced {
    def price: Double
  }

  case class PricePoint(price: Double, micros: Long) extends Timestamped
  case class BalancePoint(balance: Double, micros: Long) extends Timestamped

  sealed trait QuoteSide
  case object Bid extends QuoteSide
  case object Ask extends QuoteSide

  case class Account(exchange: String, security: String) {
    override def toString = s"$exchange/$security"
  }
  object Account {
    def parse(acc: String) = {
      val parts = acc.split("/")
      Account(parts(0), parts(1))
    }

    implicit val accountKeyEncoder: KeyEncoder[Account] = new KeyEncoder[Account] {
      override def apply(key: Account) = key.toString
    }

    implicit val accountKeyDecoder: KeyDecoder[Account] = new KeyDecoder[Account] {
      override def apply(key: String) = Some(parse(key))
    }
  }

  case class Market(exchange: String, symbol: String) {
    override def toString = s"$exchange/$symbol"
  }
  object Market {
    def parse(market: String) = {
      val parts = market.split("/")
      Market(parts(0), parts(1))
    }

    def parseOpt(market: String): Option[Market] = {
      var ret: Option[Market] = None
      try {
        ret = Some(parse(market))
      }
      ret
    }

    implicit val marketKeyEncoder: KeyEncoder[Market] = new KeyEncoder[Market] {
      override def apply(key: Market) = key.toString
    }

    implicit val marketKeyDecoder: KeyDecoder[Market] = new KeyDecoder[Market] {
      override def apply(key: String) = Some(parse(key))
    }

    implicit def pairToMarket(pair: (String, String)): Market = Market(pair._1, pair._2)
  }

  case class Tick(events: Seq[Any] = Seq.empty, exchange: Option[String] = None)

  sealed trait StrategyEvent
  case class StrategyOrderEvent(targetId: TargetId, event: OrderEvent) extends StrategyEvent

  sealed trait StrategyCommand

  final case class TargetId(instrument: Instrument, key: String)
}
