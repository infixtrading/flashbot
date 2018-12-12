package com.infixtrading.flashbot.core

import scala.collection.immutable.Set

class Pegs(val pegs: Set[(String, String)]) extends AnyVal {
  def of(asset: String): Set[String] = of(Set(asset))

  def of(assets: Set[String]): Set[String] = {
    val pegMap = pegs.flatMap { case (a, b) => Set((a, b), (b, a))}.toMap
    pegMap.filterKeys(assets.contains).values.toSet
  }
}

object Pegs {
  implicit def pegs(set: Set[(String, String)]): Pegs = new Pegs(set)

  def default: Pegs = Set(
    ("usd", "usdt"),
    ("usd", "usdc"),
    ("usd", "dai"),
    ("usd", "tusd"),
    ("usd", "bitusd"),
    ("btc", "xbt")
  )
}
