package com.infixtrading.flashbot.core
import java.time.Instant

import com.infixtrading.flashbot.models.core.TimeRange
import com.infixtrading.flashbot.util.time.parseDuration

import scala.concurrent.duration._

sealed trait TradingSessionMode {
  def timeRange(now: Instant): TimeRange
}
case class Backtest(range: TimeRange) extends TradingSessionMode {
  override def timeRange(now: Instant) = range
}
case class Paper(lookback: Duration) extends TradingSessionMode {
  override def timeRange(now: Instant) = TimeRange.build(now, lookback)
}
case object Live extends TradingSessionMode {
  override def timeRange(now: Instant) = TimeRange.build(now, "now")
}

object TradingSessionMode {
  def apply(str: String): TradingSessionMode = str.split(':').toList match {
    case "live" :: Nil => Live
    case "paper" :: Nil => Paper(0 seconds)
    case "paper" :: durStr :: Nil => Paper(parseDuration(durStr))
  }
}


