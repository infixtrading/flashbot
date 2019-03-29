package flashbot.models

import java.time.Instant

import flashbot.util.time
import io.circe.{Decoder, Encoder, Json}

import scala.concurrent.duration._
import scala.language.postfixOps

import TradingSessionMode._

sealed trait TradingSessionMode {
  def timeRange(now: Instant): TimeRange

  def lookback: FiniteDuration

  override def toString = this match {
    case Live => "live"
    case Paper(x) if x.length == 0 => "paper"
    case Paper(x) => "paper:" + time.printDuration(x)
  }

  def isBacktest: Boolean
}
case class Backtest(range: TimeRange) extends TradingSessionMode {
  override def timeRange(now: Instant) = range
  override def lookback = 0 seconds
  override def isBacktest = true
}
case class Paper(lookback: FiniteDuration = 0 seconds) extends TradingSessionMode {
  override def timeRange(now: Instant) = TimeRange.build(now, lookback)
  override def isBacktest = false
}
case object Live extends TradingSessionMode {
  override def timeRange(now: Instant) = TimeRange.build(now, "now")
  override def lookback = 0 seconds
  override def isBacktest = false
}

object TradingSessionMode {
  def apply(str: String): TradingSessionMode = str.split(':').toList match {
    case "live" :: Nil => Live
    case "paper" :: Nil => Paper(0 seconds)
    case "paper" :: durStr :: Nil => Paper(time.parseDuration(durStr))
  }

  implicit val durationDecoder: Decoder[FiniteDuration] = time.DurationDecoder

  implicit val en: Encoder[TradingSessionMode] = Encoder.encodeString.contramap(_.toString)

  implicit val de: Decoder[TradingSessionMode] = Decoder.decodeString.map(TradingSessionMode.apply)
}


