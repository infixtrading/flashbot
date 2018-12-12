package com.infixtrading.flashbot

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

  case class Trade(id: String, micros: Long, price: Double, size: Double, side: Side)
    extends Timestamped with Priced

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


  case class Tick(events: Seq[Any] = Seq.empty, exchange: Option[String] = None)

  sealed trait StrategyEvent
  case class StrategyOrderEvent(targetId: TargetId, event: OrderEvent) extends StrategyEvent

  sealed trait StrategyCommand

  final case class TargetId(instrument: Instrument, key: String)
}
