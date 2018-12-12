package com.infixtrading.flashbot.report

import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.core.{Account, Instrument, Market, Position}
import com.infixtrading.flashbot.report.ReportDelta._
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
                        price: Double,
                        size: Double) extends ReportEvent with Timestamped
  object TradeEvent {
    implicit def tradeEventEn: Encoder[TradeEvent] = deriveEncoder[TradeEvent]
    implicit def tradeEventDe: Decoder[TradeEvent] = deriveDecoder[TradeEvent]
  }

  case class PriceEvent(exchange: String,
                        instrument: Instrument,
                        price: Double,
                        micros: Long) extends ReportEvent with Timestamped
  case class PositionEvent(market: Market,
                           position: Position,
                           micros: Long) extends ReportEvent with Timestamped
  case class BalanceEvent(account: Account,
                          balance: Double,
                          micros: Long) extends ReportEvent with Timestamped

  case class TimeSeriesEvent(key: String, value: Double, micros: Long)
    extends ReportEvent with Timestamped

  case class TimeSeriesCandle(key: String, candle: Candle)
    extends ReportEvent with Timestamped {
    override def micros: Long = candle.micros
  }

  case class CollectionEvent(name: String, item: Json) extends ReportEvent
  object CollectionEvent {
    implicit def collEventEn: Encoder[CollectionEvent] = deriveEncoder
    implicit def collEventDe: Decoder[CollectionEvent] = deriveDecoder
  }

  case class ReportValueEvent(event: ValueEvent) extends ReportEvent

  implicit def reportValueEvent(event: ValueEvent): ReportEvent = ReportValueEvent(event)

//  implicit val reportEventEn: Encoder[ReportEvent] = deriveEncoder[ReportEvent]
//  implicit val reportEventDe: Decoder[ReportEvent] = deriveDecoder[ReportEvent]

}
