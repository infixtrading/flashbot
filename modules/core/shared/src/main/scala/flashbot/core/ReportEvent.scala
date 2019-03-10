package flashbot.core

import flashbot.core.Num.Num
import flashbot.core.Report._
import flashbot.core.ReportDelta._
import flashbot.models.core._
import io.circe._
import io.circe.generic.semiauto._

/**
  * These are events that are emitted by the session, to be sent to the report.
  */
sealed trait ReportEvent

object ReportEvent {
  case class TradeEvent(id: Option[String],
                        exchange: String,
                        product: String,
                        micros: Long,
                        price: Num,
                        size: Num) extends ReportEvent with Timestamped
  object TradeEvent {
    implicit def tradeEventEn: Encoder[TradeEvent] = deriveEncoder[TradeEvent]
    implicit def tradeEventDe: Decoder[TradeEvent] = deriveDecoder[TradeEvent]
  }

  case class PriceEvent(market: Market,
                        price: Num,
                        micros: Long) extends ReportEvent with Timestamped

  case class PositionEvent(market: Market,
                           position: Position,
                           micros: Long) extends ReportEvent with Timestamped

  case class BalanceEvent(account: Account,
                          balance: Num,
                          micros: Long) extends ReportEvent with Timestamped

  sealed trait CandleEvent extends ReportEvent {
    def series: String
  }
  case class CandleUpdate(series: String, candle: Candle) extends CandleEvent
  case class CandleAdd(series: String, candle: Candle) extends CandleEvent
  object CandleEvent {
    implicit def candleEventEn: Encoder[CandleEvent] = deriveEncoder
    implicit def candleEventDe: Decoder[CandleEvent] = deriveDecoder
  }


  case class CollectionEvent(name: String, item: Json) extends ReportEvent
  object CollectionEvent {
    implicit def collEventEn: Encoder[CollectionEvent] = deriveEncoder
    implicit def collEventDe: Decoder[CollectionEvent] = deriveDecoder
  }

  case class SessionComplete(error: Option[ReportError]) extends ReportEvent

  case class ReportValueEvent(event: ValueEvent) extends ReportEvent

  implicit def reportValueEvent(event: ValueEvent): ReportEvent = ReportValueEvent(event)

  implicit def reportEventEn(implicit valEventEn: Encoder[ValueEvent]): Encoder[ReportEvent] =
    deriveEncoder[ReportEvent]
  implicit def reportEventDe(implicit valEventDe: Decoder[ValueEvent]): Decoder[ReportEvent] =
    deriveDecoder[ReportEvent]

}
