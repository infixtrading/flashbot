package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.{DataPath, OrderBook}
import org.scalatest.{FlatSpec, Matchers}
import com.infixtrading.flashbot.util.stream._

class PathsTest extends FlatSpec with Matchers {
  "DataPath" should "match correctly" in {
    val path: DataPath[Trade] = "coinbase/btc_usd/trades"
    val bookpath: DataPath[OrderBook] = "coinbase/btc_usd/book"

    path.matches(bookpath) shouldBe false

  }
}
