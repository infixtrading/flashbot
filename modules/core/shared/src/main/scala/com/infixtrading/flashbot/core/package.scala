package com.infixtrading.flashbot

import com.infixtrading.flashbot.models.core.Order.Side
import com.infixtrading.flashbot.util.time.parseDuration
import java.time.Instant

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

import scala.concurrent.duration.Duration

package object core {

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
    def price: Double = lastTradePrice
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

  object QuoteSide {
    implicit val en: Encoder[QuoteSide] = deriveEncoder
    implicit val de: Decoder[QuoteSide] = deriveDecoder
  }


  case class Tick(events: Seq[Any] = Seq.empty, exchange: Option[String] = None)

  sealed trait StrategyEvent
  case class StrategyOrderEvent(targetId: TargetId, event: OrderEvent) extends StrategyEvent

  sealed trait StrategyCommand

  final case class TargetId(instrument: Instrument, key: String)
}
