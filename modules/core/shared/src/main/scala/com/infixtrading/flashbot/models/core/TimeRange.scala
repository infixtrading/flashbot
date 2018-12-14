package com.infixtrading.flashbot.models.core

import java.time.Instant

import com.infixtrading.flashbot.util.time._

import scala.concurrent.duration.{Duration, FiniteDuration}

case class TimeRange(start: Long, end: Long = Long.MaxValue) {
  def startInstant: Instant = Instant.ofEpochMilli(start / 1000)
  def endInstant: Instant = Instant.ofEpochMilli(end / 1000)
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
