package flashbot.core

import java.time.{Instant, ZonedDateTime}
import java.util.Date

import flashbot.util.time._

trait Timestamped {
  def micros: Long
  def millis: Long = micros / 1000
  def instant: Instant = micros.microsToInstant
  def date: Date = new Date(micros / 1000)
  def zdt: ZonedDateTime = micros.microsToZdt
}
object Timestamped {
  val ordering: Ordering[Timestamped] = Ordering.by(_.micros)
}
