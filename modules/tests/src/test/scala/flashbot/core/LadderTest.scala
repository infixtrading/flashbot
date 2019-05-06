package flashbot.core

import flashbot.models.Ladder
import org.scalatest.{FlatSpec, Matchers}

class LadderTest extends FlatSpec with Matchers {
  "Ladder" should "load asks and bids" in {
    val ladder = new Ladder(depth = 25, .5)
    ladder.updateLevel(Bid, 99, 1)
    ladder.updateLevel(Bid, 98.5, 2)
    ladder.updateLevel(Ask, 101, 2)

    ladder.asks.size() shouldBe 1
    ladder.asks.bestPrice shouldBe 101
    ladder.asks.bestQty shouldBe 2
    ladder.asks.worstPrice shouldBe 101
    ladder.asks.worstQty shouldBe 2

    ladder.bids.size() shouldBe 2
    ladder.bids.bestPrice shouldBe 99
    ladder.bids.bestQty shouldBe 1
    ladder.bids.worstPrice shouldBe 98.5
    ladder.bids.worstQty shouldBe 2
  }
}
