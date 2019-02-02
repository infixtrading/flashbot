package flashbot.core

case class Tick(events: Seq[Any],
                exchange: Option[String],
                micros: Long)

object Tick {
  implicit val ordering: Ordering[Tick] = Ordering.by(_.micros)
}

