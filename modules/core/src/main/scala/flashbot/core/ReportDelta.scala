package flashbot.core

import flashbot.core.ReportEvent._
import io.circe._
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

  @JsonCodec
  sealed trait ValueEvent extends ReportDelta
  case class PutValueEvent(key: String, fmtName: String, value: Json) extends ValueEvent
  case class UpdateValueEvent(key: String, delta: Json) extends ValueEvent
  case class RemoveValueEvent(key: String) extends ValueEvent
}

