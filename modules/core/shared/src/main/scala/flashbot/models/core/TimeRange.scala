package flashbot.models.core

import java.math.BigInteger
import java.time.Instant

import flashbot.util.time._
import io.circe.generic.JsonCodec

import scala.concurrent.duration.Duration

/**
  * Specifies a start and end time in micros. This differs from from/to in DataSelection in that it
  * has no semantics for polling.
  */
@JsonCodec
case class TimeRange(start: Long, end: Long = Long.MaxValue) {
  def startInstant: Instant = Instant.ofEpochMilli(start / 1000)
  def endInstant: Instant = Instant.ofEpochMilli(end / 1000)

  private def roundToSecs(micros: Long): Long = {
    val remainder = micros % 1000000
    (micros - remainder) + (if (remainder > 500000) 1 else 0)
  }

  def roundToSecs: TimeRange = {
    TimeRange(roundToSecs(start), roundToSecs(end))
  }
}

object TimeRange {
  def build(now: Instant, from: String): TimeRange = {
    parseTime(now, from) match {
      case Left(dur) => build(now, dur)
      case Right(inst) => TimeRange(inst.toEpochMilli * 1000, now.toEpochMilli * 1000)
    }
  }

  def build(now: Instant, from: Duration): TimeRange =
    TimeRange(now.minusMillis(from.toMillis).toEpochMilli * 1000, now.toEpochMilli * 1000)

  def build(now: Instant, from: String, to: String): TimeRange = {
    val fromT = parseTime(now, from)
    val toT = parseTime(now, to)
    (fromT, toT) match {
      case (Right(inst), Left(dur)) => TimeRange(
        inst.toEpochMilli * 1000,
        inst.plusMillis(dur.toMillis).toEpochMilli * 1000)
      case (Left(dur), Right(inst)) => TimeRange(
        inst.minusMillis(dur.toMillis).toEpochMilli * 1000,
        inst.toEpochMilli * 1000)
    }
  }
}
