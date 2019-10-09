package flashbot.core

import org.scalatest.{FlatSpec, Matchers}
import flashbot.util.stream._
import flashbot.models.{DataPath, OrderBook}

class PathsTest extends FlatSpec with Matchers {
  "DataPath" should "match correctly" in {
    val path: DataPath[Trade] = "coinbase/btc_usd/trades"
    val bookpath: DataPath[OrderBook] = "coinbase/btc_usd/book"

    path.matches(bookpath) shouldBe false

  }
}
