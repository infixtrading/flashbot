package flashbot.core

import flashbot.models.Ladder
import flashbot.util.TableUtil
import org.scalatest.{FlatSpec, Matchers}

class LadderTest extends FlatSpec with Matchers {
  "Ladder" should "load asks and bids" in {
    val ladder = new Ladder(depth = 25, .5)
    ladder.qtyAtPrice(99) shouldEqual 0

    ladder.updateLevel(Bid, 99, 1)
    ladder.updateLevel(Bid, 98.5, 2)
    ladder.updateLevel(Ask, 101, 2)

    ladder.asks.size shouldBe 1
    ladder.asks.bestPrice shouldBe 101
    ladder.asks.bestQty shouldBe 2
    ladder.asks.worstPrice shouldBe 101
    ladder.asks.worstQty shouldBe 2

    ladder.bids.size shouldBe 2
    ladder.bids.bestPrice shouldBe 99
    ladder.bids.bestQty shouldBe 1
    ladder.bids.worstPrice shouldBe 98.5
    ladder.bids.worstQty shouldBe 2
  }

  "Ladder" should "go back to it's original state if orders are removed" in {
    val ladder = new Ladder(10, 1)

    ladder.bids.depth shouldEqual 0

    ladder.updateLevel(Bid, 101, 1)
    ladder.updateLevel(Bid, 102, 1)
    ladder.updateLevel(Bid, 103, 1)

    ladder.bids.depth shouldEqual 3

    // Removing from worst
    ladder.updateLevel(Bid, 101, 0)
    ladder.updateLevel(Bid, 102, 0)
    ladder.updateLevel(Bid, 103, 0)

    ladder.isEmpty shouldEqual true
    ladder.bids.isEmpty shouldBe true

    // Re-add
    ladder.updateLevel(Bid, 101, 1)
    ladder.updateLevel(Bid, 102, 1)
    ladder.updateLevel(Bid, 103, 1)

    // Also to asks
    ladder.updateLevel(Ask, 101, 1)
    ladder.updateLevel(Ask, 102, 1)

    ladder.isEmpty shouldEqual false
    ladder.bids.depth shouldEqual 3
    ladder.asks.depth shouldEqual 2

    // Removing from best
    ladder.updateLevel(Ask, 102, 0)
    ladder.updateLevel(Ask, 101, 0)

    ladder.updateLevel(Bid, 103, 0)
    ladder.updateLevel(Bid, 102, 0)
    ladder.updateLevel(Bid, 101, 0)

    ladder.isEmpty shouldEqual true
  }

  "Ladder" should "be buildable from OrderBook" in {
//    val nowMicros = System.currentTimeMillis() * 1000
    val seq = OrderBookTap(.01, 200)
      .map(Ladder.fromOrderBook(200, _))
      .zipWithIndex
      .toVector
    TableUtil.render(seq.take(2))
  }

  "Ladder" should "truncate sparse order books" in {
    // Test data extracted from a BitMEX book snapshot

    val ladder = new Ladder(depth = 5, .5)
    ladder.updateLevel(Ask, 1000000, 3)
    ladder.asks.depth shouldEqual 1

    ladder.updateLevel(Ask, 999999, 345)
    ladder.asks.depth shouldEqual 2
    ladder.asks.worstPrice shouldEqual 1000000
    ladder.asks.bestPrice shouldEqual 999999

    ladder.updateLevel(Ask, 999998, 6)
    ladder.asks.depth shouldEqual 3
    ladder.asks.worstPrice shouldEqual 1000000
    ladder.asks.bestPrice shouldEqual 999998

    ladder.updateLevel(Ask, 900000, 400)
    ladder.asks.depth shouldEqual 4
    ladder.asks.worstPrice shouldEqual 1000000
    ladder.asks.bestPrice shouldEqual 900000

    ladder.updateLevel(Ask, 826446.5, 484000)
    ladder.asks.depth shouldEqual 5
    ladder.asks.worstPrice shouldEqual 1000000
    ladder.asks.bestPrice shouldEqual 826446.5

    ladder.updateLevel(Ask, 819672.5, 19558)
    ladder.asks.depth shouldEqual 5
    ladder.asks.worstPrice shouldEqual 999999
    ladder.asks.bestPrice shouldEqual 819672.5
  }


  /**
    * (Updating,LadderDelta(ask,1000000.0,3.0))
    * (Updating,LadderDelta(ask,999999.0,345.0))
    * Level: -2
    * Truncated. Depth is: 2
    * (Updating,LadderDelta(ask,999998.0,6.0))
    * Level: -2
    * Truncated. Depth is: 3
    * (Updating,LadderDelta(ask,900000.0,400.0))
    * Level: -199996
    * Truncated. Depth is: 4
    * (Updating,LadderDelta(ask,826446.5,484000.0))
    * Level: -147107
    * Truncated. Depth is: 5
    * (Updating,LadderDelta(ask,819672.5,19558.0))
    * Level: -13548
    * Truncated. Depth is: 6
    * (Updating,LadderDelta(ask,800000.0,4092.0))
    * Level: -39345
    * Truncated. Depth is: 7
    * (Updating,LadderDelta(ask,793651.0,3967.0))
    * Level: -12698
    * Truncated. Depth is: 8
    * (Updating,LadderDelta(ask,420000.0,5.0))
    * Level: -747302
    * Truncated. Depth is: 9
    * (Updating,LadderDelta(ask,351450.0,200.0))
    * Level: -137100
    * Truncated. Depth is: 10
    * (Updating,LadderDelta(ask,300000.0,2500.0))
    * Level: -102900
    * Truncated. Depth is: 11
    * (Updating,LadderDelta(ask,250000.0,653.0))
    * Level: -100000
    * Truncated. Depth is: 12
    * (Updating,LadderDelta(ask,249000.0,1.0))
    * Level: -2000
    * Truncated. Depth is: 13
    * (Updating,LadderDelta(ask,220000.0,550.0))
    * Level: -58000
    * Truncated. Depth is: 14
    * (Updating,LadderDelta(ask,200000.0,1016024.0))
    * Level: -40000
    * Truncated. Depth is: 15
    * (Updating,LadderDelta(ask,150000.0,100.0))
    * Level: -100000
    * Truncated. Depth is: 16
    * (Updating,LadderDelta(ask,141250.0,6800.0))
    * Level: -17500
    * Truncated. Depth is: 17
    * (Updating,LadderDelta(ask,100000.0,3433.0))
    * Level: -82500
    * Truncated. Depth is: 18
    * (Updating,LadderDelta(ask,99800.0,700.0))
    * Level: -400
    * Truncated. Depth is: 19
    * (Updating,LadderDelta(ask,99400.0,300.0))
    * Level: -800
    * Truncated. Depth is: 20
    * (Updating,LadderDelta(ask,99000.0,79.0))
    * Level: -800
    * Truncated. Depth is: 21
    * (Updating,LadderDelta(ask,98000.0,100000.0))
    * Level: -2000
    * Truncated. Depth is: 22
    * (Updating,LadderDelta(ask,97686.0,700.0))
    * Level: -628
    * Truncated. Depth is: 23
    * (Updating,LadderDelta(ask,94400.0,8700.0))
    * Level: -6572
    * Truncated. Depth is: 24
    * (Updating,LadderDelta(ask,92400.0,100.0))
    * Level: -4000
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,89000.0,9400.0))
    * Level: -6800
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,88888.0,7000.0))
    * Level: -7023
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,88673.0,6000.0))
    * Level: -7452
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,88080.0,600.0))
    * Level: -8637
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,87750.5,70.0))
    * Level: -9295
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,87730.5,100.0))
    * Level: -9334
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,87036.0,2000.0))
    * Level: -10722
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,85564.0,100.0))
    * Level: -13665
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,85000.5,501.0))
    * Level: -14791
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,84500.0,50.0))
    * Level: -15791
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,83621.0,10.0))
    * Level: -17548
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,81500.0,500.0))
    * Level: -21789
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,80000.0,2000.0))
    * Level: -24788
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,78683.0,5000.0))
    * Level: -27421
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,78315.0,70.0))
    * Level: -28156
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,77779.0,24.0))
    * Level: -29227
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,77570.0,6000.0))
    * Level: -29644
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,75000.0,1000.0))
    * Level: -34783
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,71175.0,10.0))
    * Level: -42432
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,71045.0,10.0))
    * Level: -42691
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,70000.0,1000.0))
    * Level: -44780
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,69800.0,500.0))
    * Level: -45179
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,66494.0,6500.0))
    * Level: -51790
    * Truncated. Depth is: 25
    * (Updating,LadderDelta(ask,65688.0,400000.0))
    */
}
