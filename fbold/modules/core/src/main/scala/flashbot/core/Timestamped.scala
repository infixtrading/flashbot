package flashbot.core

import java.time.Instant
import flashbot.util.time._

trait Timestamped {
  def micros: Long
  def instant: Instant = micros.microsToInstant
}
object Timestamped {
  val ordering: Ordering[Timestamped] = Ordering.by(_.micros)
}
