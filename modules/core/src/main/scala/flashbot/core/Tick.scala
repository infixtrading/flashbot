package flashbot.core

import flashbot.models.api.TradingSessionEvent

trait SessionInput {
  def micros: Long
}

object SessionInput {
  implicit val ordering: Ordering[SessionInput] = Ordering.by(_.micros)
}

case class Tick(micros: Long,
                exchange: Option[String],
                events: Option[Array[TradingSessionEvent]]) extends SessionInput

object Tick {
  implicit val ordering: Ordering[Tick] = Ordering.by(_.micros)
}

