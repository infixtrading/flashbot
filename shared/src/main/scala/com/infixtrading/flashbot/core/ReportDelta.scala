package com.infixtrading.flashbot.core

import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import io.circe.Decoder.Result
import com.infixtrading.flashbot.core._

/**
  * Changes to reports need to be saved as data (engine events), so updates must happen in two
  * steps. First, a report receives a ReportEvent and emits a sequence of ReportDeltas. Then the
  * client code may do whatever it wants with the deltas. Probably fold them over the previous
  * report. Sometimes it will also persist the deltas.
  */
sealed trait ReportDelta

object ReportDelta {
  case class TradeAdd(tradeEvent: ReportEvent.TradeEvent) extends ReportDelta
  case class CollectionAdd(collectionEvent: ReportEvent.CollectionEvent) extends ReportDelta

  sealed trait CandleEvent extends ReportDelta {
    def series: String
  }
  case class CandleUpdate(series: String, candle: Candle) extends CandleEvent
  case class CandleAdd(series: String, candle: Candle) extends CandleEvent
  //  object CandleEvent {
  //    implicit def candleEventEn: Encoder[CandleEvent] = deriveEncoder
  //    implicit def candleEventDe: Decoder[CandleEvent] = deriveDecoder
  //  }

  sealed trait ValueEvent extends ReportDelta
  case class PutValueEvent[T](key: String, fmtName: String, value: T) extends ValueEvent
  case class UpdateValueEvent[T](key: String, delta: T) extends ValueEvent
  case class RemoveValueEvent(key: String) extends ValueEvent

  val putValueEvJsonEn: Encoder[PutValueEvent[Json]] = deriveEncoder
  val putValueEvJsonDe: Decoder[PutValueEvent[Json]] = deriveDecoder
  val updateValueEvJsonEn: Encoder[UpdateValueEvent[Json]] = deriveEncoder
  val updateValueEvJsonDe: Decoder[UpdateValueEvent[Json]] = deriveDecoder
  val rmValEn: Encoder[RemoveValueEvent] = deriveEncoder
  val rmValDe: Decoder[RemoveValueEvent] = deriveDecoder

  implicit def valueEventEn(implicit report: Report): Encoder[ValueEvent] = new Encoder[ValueEvent] {
    override def apply(a: ValueEvent): Json = a match {
      case ev @ PutValueEvent(key, fmtName, value) =>
        val putValueEventJson: PutValueEvent[Json] =
          ev.copy(value = putValEn(DeltaFmt.formats(fmtName), value))
        putValueEvJsonEn(putValueEventJson)

      case ev @ UpdateValueEvent(key, delta) =>
        val fmt = DeltaFmt.formats(report.values(key).fmtName)
        val updateValueEventJson: UpdateValueEvent[Json] =
          ev.copy(delta = updateDeltaEn(fmt, delta))
        updateValueEvJsonEn(updateValueEventJson)

      case ev: RemoveValueEvent => rmValEn(ev)
    }

    def putValEn[T](fmt: DeltaFmtJson[T], value: Any): Json = {
      value match {
        case typedValue: T =>
          fmt.modelEn(typedValue)
      }
    }

    def updateDeltaEn[T](fmt: DeltaFmtJson[T], delta: Any): Json = {
      delta match {
        case typedDelta: fmt.D =>
          fmt.deltaEn(typedDelta)
      }
    }
  }

  implicit def valueEventDe(implicit report: Report): Decoder[ValueEvent] = new Decoder[ValueEvent] {
    override def apply(c: HCursor): Result[ValueEvent] = {
      // Try to decode as RemoveValueEvent
      rmValDe(c) match {
        // Try to decode as PutValueEvent
        case Left(_) => putValueEvJsonDe(c) match {
          // Must decode as UpdateValueEvent
          case Left(_) => updateValueEvJsonDe(c) match {
            case Right(UpdateValueEvent(key, deltaJson)) =>
              val fmt = DeltaFmt.formats(report.values(key).fmtName)
              fmt.deltaDe.decodeJson(deltaJson).right.map(UpdateValueEvent(key, _))
            case err => err
          }
          case Right(PutValueEvent(key, fmtName, jsonVal)) =>
            val fmt = DeltaFmt.formats(fmtName)
            fmt.modelDe.decodeJson(jsonVal).right.map(PutValueEvent(key, fmtName, _))
        }
        case right => right
      }
    }
  }

  implicit def reportDeltaEn(implicit report: Report): Encoder[ReportDelta] = {
    implicit val veEn: Encoder[ValueEvent] = valueEventEn
    implicitly[Encoder[ReportDelta]]
  }

  implicit def reportDeltaDe(implicit report: Report): Decoder[ReportDelta] = {
    implicit val veDe: Decoder[ValueEvent] = valueEventDe
    implicitly[Decoder[ReportDelta]]
  }

}
