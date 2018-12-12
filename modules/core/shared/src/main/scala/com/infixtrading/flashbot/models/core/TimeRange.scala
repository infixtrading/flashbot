package com.infixtrading.flashbot.models.core

import java.time.Instant
import com.infixtrading.flashbot.util.time.parseTime

case class TimeRange(from: Long, to: Long = Long.MaxValue)

object TimeRange {
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
