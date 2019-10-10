package flashbot.core

import java.time.{Instant, ZonedDateTime}
import java.util.Date

import flashbot.util.time._
import org.ta4j.core.Bar

trait Timestamped {
  def micros: Long
  def millis: Long = micros / 1000
  def instant: Instant = micros.microsToInstant
  def date: Date = new Date(micros / 1000)
  def zdt: ZonedDateTime = micros.microsToZdt
}
object Timestamped {
  val ordering: Ordering[Timestamped] = Ordering.by(_.micros)

  trait HasTime[T] {
    def micros(item: T): Long
  }
  object HasTime {
    def apply[T: HasTime]: HasTime[T] = implicitly

    implicit def timestampedHasTime[T <: Timestamped]: HasTime[T] = (item: T) => item.micros

    implicit def priceSeriesHasTime: HasTime[(Instant, Double)] = ps => ps._1.micros

    implicit def priceVolSeriesHasTime: HasTime[(Instant, Double, Double)] = ps => ps._1.micros

    implicit def barHasTime: HasTime[Bar] = (bar: Bar) => bar.getBeginTime.micros
  }
}
