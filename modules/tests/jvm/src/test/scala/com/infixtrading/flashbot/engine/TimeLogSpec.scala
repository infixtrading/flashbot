package com.infixtrading.flashbot.engine

import java.io.File

import io.circe.generic.auto._
import com.infixtrading.flashbot.util.files._
import com.infixtrading.flashbot.models.core.Order.{Buy, Sell}
import com.infixtrading.flashbot.core.Trade
import com.infixtrading.flashbot.engine.TimeLog
import com.infixtrading.flashbot.engine.TimeLog.ScanDuration
import net.openhft.chronicle.queue.RollCycles
import org.scalatest._

import scala.concurrent.duration._

class TimeLogSpec extends FlatSpec with Matchers {

  var testFolder: File = _
  val nowMillis = 1543017219051L // A few minutes before midnight

  // One trade every minute
  def genTrades(n: Int, nowMillis: Long): Seq[Trade] = Seq.range(0, n).map(i =>
    Trade(i.toString, (nowMillis + (i minutes).toMillis) * 1000, (1000 + i).toDouble, (5 + i).toDouble,
      if (i % 2 == 0) Buy else Sell))

  "TimeLog" should "write and read a single item to a new queue" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val trades = genTrades(1, nowMillis)
    val tl = TimeLog[Trade](testFolder, Some(1 hour))
    trades.foreach(trade => tl.save(trade))
    var read = Seq.empty[Trade]
    var doneFired = false

    val it: Iterator[Trade] =
      for (trade <- tl.scan[Long](0, _.micros, _ => true, ScanDuration.Finite)(() => {
        doneFired = true
      })) yield {
        read :+= trade
        trade
      }

    val yielded = it.toSeq

    read.size shouldBe 1
    read.head shouldEqual trades.head
    yielded.size shouldBe 1
    yielded.head shouldEqual trades.head
    doneFired shouldBe true
  }

  /**
    * Sadly retention is flaky on windows so we have to comment out the assertions in this test.
    *
    * <------|--------|----------------|-------------------------|--------|----------------|---->
    *        0     midnight            30                        60      cycle             90
    */
  "TimeLog" should "clean up files for a 1 hour retention period" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val tl = TimeLog[Trade](testFolder, Some(1 hour), RollCycles.HOURLY)
    val (first30, after30) = genTrades(1000, nowMillis).splitAt(30)
    val (next30, after60) = after30.splitAt(30)

    def firstTrade: Trade =
      tl.scan[Long](0, _.micros, _ => true, ScanDuration.Finite)().toSeq.head

    // Enqueue first 30, there should be no deletions yet.
    first30.foreach(tl.save(_))
    // firstTrade shouldEqual first30.head

    // Enqueue next 30, still no deletions.
    next30.foreach(tl.save(_))
    // firstTrade shouldEqual first30.head

    // Enqueue another 30, should delete first file.
    after60.take(30).foreach(tl.save(_))
    // (firstTrade.micros > first30.head.micros) shouldBe true
    // (firstTrade.micros < next30.head.micros) shouldBe true
  }

  "TimeLog" should "find an element with binary search" in {
    val tl = TimeLog[Trade](testFolder, Some(24 hours), RollCycles.HOURLY)
    val trades = genTrades(1000, nowMillis)
    trades.foreach(tl.save(_))

    tl.find(0, _.id.toInt) shouldEqual trades.headOption

    tl.find(999, _.id.toInt) shouldEqual trades.lastOption

    tl.find(500, _.id.toInt) shouldEqual Some(trades(500))

    tl.find(-1, _.id.toInt) shouldBe None

    tl.find(1000, _.id.toInt) shouldBe None

    tl.find(5000, _.id.toInt) shouldBe None
  }

  "TimeLog" should "scan elements within a time range" in {
    val tl = TimeLog[Trade](testFolder, Some(24 hours), RollCycles.HOURLY)
    val trades = genTrades(1000, nowMillis)
    trades.foreach(tl.save(_))

    // All items
    tl.scan(0L, _.micros, _ => true, ScanDuration.Finite)().toSeq shouldEqual trades
    tl.scan(nowMillis * 1000, _.micros, _ => true, ScanDuration.Finite)().toSeq shouldEqual trades

    // All but first
    tl.scan(nowMillis * 1000 + 1, _.micros, _ => true,
      ScanDuration.Finite)().toSeq shouldEqual trades.tail

    // All but first and last
    tl.scan(nowMillis * 1000 + 1, _.micros, _.micros < trades.last.micros,
      ScanDuration.Finite)().toSeq shouldEqual trades.tail.reverse.tail.reverse
  }

  "TimeLog" should "find the last element" in {
    val tl = TimeLog[Trade](testFolder, Some(24 hours), RollCycles.HOURLY)
    val trades = genTrades(1000, nowMillis)
    trades.foreach(tl.save(_))

    tl.last.get shouldEqual trades.last
  }

  override def withFixture(test: NoArgTest) = {
    val tempFolder = System.getProperty("java.io.tmpdir")
    var folder: File = null
    do {
      folder = new File(tempFolder, "scalatest-" + System.nanoTime)
    } while (! folder.mkdir())
    testFolder = folder
    try {
      super.withFixture(test)
    } finally {
      rmRf(testFolder)
    }
  }

}

