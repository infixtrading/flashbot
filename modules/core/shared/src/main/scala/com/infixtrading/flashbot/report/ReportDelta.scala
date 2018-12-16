package com.infixtrading.flashbot.report

import io.circe.Decoder.Result
import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.models.core.Candle
import com.infixtrading.flashbot.report.ReportEvent._
import io.circe.generic.JsonCodec

/**
  * Changes to reports need to be saved as data (engine events), so updates must happen in two
  * steps. First, a report receives a ReportEvent and emits a sequence of ReportDeltas. Then the
  * client code may do whatever it wants with the deltas. Probably fold them over the previous
  * report. Sometimes it will also persist the deltas.
  */
@JsonCodec sealed trait ReportDelta

object ReportDelta {
  case class TradeAdd(tradeEvent: TradeEvent) extends ReportDelta
  case class CollectionAdd(collectionEvent: CollectionEvent) extends ReportDelta

  /**
    * When the event and the corresponding delta have no meaningful difference.
    */
  case class RawEvent(re: ReportEvent) extends ReportDelta

  sealed trait CandleEvent extends ReportDelta {
    def series: String
  }
  case class CandleUpdate(series: String, candle: Candle) extends CandleEvent
  case class CandleAdd(series: String, candle: Candle) extends CandleEvent
  object CandleEvent {
    implicit def candleEventEn: Encoder[CandleEvent] = deriveEncoder
    implicit def candleEventDe: Decoder[CandleEvent] = deriveDecoder
  }

  sealed trait ValueEvent extends ReportDelta
  case class PutValueEvent(key: String, fmtName: String, value: Json) extends ValueEvent
  case class UpdateValueEvent(key: String, delta: Json) extends ValueEvent
  case class RemoveValueEvent(key: String) extends ValueEvent

//  val putValueEvJsonEn: Encoder[PutValueEvent[Json]] = deriveEncoder
//  val putValueEvJsonDe: Decoder[PutValueEvent[Json]] = deriveDecoder
//  val updateValueEvJsonEn: Encoder[UpdateValueEvent[Json]] = deriveEncoder
//  val updateValueEvJsonDe: Decoder[UpdateValueEvent[Json]] = deriveDecoder
//  val rmValEn: Encoder[RemoveValueEvent] = deriveEncoder
//  val rmValDe: Decoder[RemoveValueEvent] = deriveDecoder

//  implicit def valueEventEn(implicit report: Report): Encoder[ValueEvent] = new Encoder[ValueEvent] {
//    override def apply(a: ValueEvent): Json = a match {
//      case ev @ PutValueEvent(key, fmtName, value) =>
//        val putValueEventJson: PutValueEvent[Json] =
//          ev.copy(value = putValEn(DeltaFmt.formats(fmtName), value))
//        putValueEvJsonEn(putValueEventJson)
//
//      case ev @ UpdateValueEvent(key, delta) =>
//        val fmt = DeltaFmt.formats(report.values(key).fmtName)
//        val updateValueEventJson: UpdateValueEvent[Json] =
//          ev.copy(delta = updateDeltaEn(fmt, delta))
//        updateValueEvJsonEn(updateValueEventJson)
//
//      case ev: RemoveValueEvent => rmValEn(ev)
//    }
//
//    def putValEn[T](fmt: DeltaFmtJson[T], value: Any): Json = {
//      value match {
//        case typedValue: T =>
//          fmt.modelEn(typedValue)
//      }
//    }
//
//    def updateDeltaEn[T](fmt: DeltaFmtJson[T], delta: Any): Json = {
//      delta match {
//        case typedDelta: fmt.D =>
//          fmt.deltaEn(typedDelta)
//      }
//    }
//  }

//  def valueEventDe(implicit report: Report): Decoder[ValueEvent] = new Decoder[ValueEvent] {
//    override def apply(c: HCursor): Result[ValueEvent] = {
//      // Try to decode as RemoveValueEvent
//      rmValDe(c) match {
//        // Try to decode as PutValueEvent
//        case Left(_) => putValueEvJsonDe(c) match {
//            // Must decode as UpdateValueEvent
//          case Left(_) => updateValueEvJsonDe(c) match {
//            case Right(UpdateValueEvent(key, deltaJson)) =>
//              val fmt = DeltaFmt.formats(report.values(key).fmtName)
//              fmt.deltaDe.decodeJson(deltaJson).right.map(UpdateValueEvent(key, _))
//            case err => err
//          }
//          case Right(PutValueEvent(key, fmtName, jsonVal)) =>
//            val fmt = DeltaFmt.formats(fmtName)
//            fmt.modelDe.decodeJson(jsonVal).right.map(PutValueEvent(key, fmtName, _))
//        }
//        case right => right
//      }
//    }
//  }

//  def reportDeltaEn(implicit report: Report): Encoder[ReportDelta] = {
////    implicit val veEn: Encoder[ValueEvent] = valueEventEn(report)
//    implicitly[Encoder[TradeAdd]]
//    implicitly[Encoder[CollectionAdd]]
//    implicitly[Encoder[CandleEvent]]
//    implicitly[Encoder[ValueEvent]]
//    implicitly[Encoder[ReportDelta]]
//
//    deriveEncoder[ReportDelta]
////    implicitly[Encoder[ReportDelta]]
//  }
//
//  def reportDeltaDe(implicit report: Report): Decoder[ReportDelta] = {
////    implicit val veDe: Decoder[ValueEvent] = valueEventDe
//    deriveDecoder[ReportDelta]
////    implicitly[Decoder[ReportDelta]]
//  }

}

//object ReportDeltaEncoders {
//  import ReportDelta.TradeAdd
//  import ReportDelta.CollectionAdd
//  import ReportDelta.CandleEvent
//  import ReportDelta.ValueEvent
//
//  def reportDeltaEn(report: Report): Encoder[ReportDelta] = {
////    implicit val veEn: Encoder[ValueEvent] = valueEventEn(report)
//    implicit val r: Report = report
//
//    implicitly[Encoder[TradeAdd]]
//    implicitly[Encoder[CollectionAdd]]
//    implicitly[Encoder[CandleEvent]]
//    implicitly[Encoder[ValueEvent]]
////    implicitly[Encoder[ReportDelta]]
//
//    deriveEncoder[ReportDelta]
////    implicitly[Encoder[ReportDelta]]
//  }
//}
