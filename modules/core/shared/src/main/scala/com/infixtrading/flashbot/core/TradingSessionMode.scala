package com.infixtrading.flashbot.core
import com.infixtrading.flashbot.models.core.TimeRange

sealed trait TradingSessionMode
case class Backtest(range: TimeRange) extends TradingSessionMode
case object Paper extends TradingSessionMode
case object Live extends TradingSessionMode

object TradingSessionMode {
  def apply(str: String): TradingSessionMode = str match {
    case "live" => Live
    case "paper" => Paper
  }
}


