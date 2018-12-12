package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.Order.{Buy, Sell, Side}

case class FixedSize(amount: Double, security: String) {

  def isEmpty: Boolean = amount == 0

  def side: Side = {
    if (amount > 0) Buy
    else Sell
  }
}

object FixedSize {
  implicit def buildFixedSizeStr(str: String): FixedSize = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }
  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSize =
    FixedSize(tuple._1, tuple._2)

  def apply(amount: Double, symbol: String): FixedSize = new FixedSize(amount, symbol)
}

