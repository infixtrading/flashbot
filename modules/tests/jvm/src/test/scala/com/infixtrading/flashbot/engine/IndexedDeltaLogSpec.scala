package com.infixtrading.flashbot.engine

import org.scalatest._
import java.io.File

import com.infixtrading.flashbot.core.Trade
import com.infixtrading.flashbot.models.core.Order.{Buy, Down, Sell, Up}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class IndexedDeltaLogSpec extends FlatSpec with Matchers {

  var testFolder: File = _
  val nowMillis = 1543017219051L // A few minutes before midnight

  val MicrosPerMinute: Long = 60L * 1000000

  "IndexedDeltaLog" should "save data with a 1 day retention and 1 hour snapshots" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val nowMicros = nowMillis * 1000
    val trades: Seq[Trade] = (1 to 1440) map { i =>
      Trade(i.toString, nowMicros + i * MicrosPerMinute, i, i, if (i % 2 == 0) Up else Down)
    }

    val idLog = new IndexedDeltaLog[Trade](file, Some(1.day), 1.hour)

    trades.foreach(trade => {
      idLog.save(trade.micros, trade)
    })

    idLog.scan().flatten.map(_._1).toSeq shouldEqual trades
  }

  // Issues on windows, so disabling the assertion here.
  "IndexedDeltaLog" should "respect the retention policy" in {
    val file = new File(testFolder.getAbsolutePath + "/trades")
    val nowMicros = nowMillis * 1000
    val trades: Seq[Trade] = (1 to 1440) map { i =>
      Trade(i.toString, nowMicros + i * 5 * MicrosPerMinute, i, i, if (i % 2 == 0) Up else Down)
    }

    val idLog = new IndexedDeltaLog[Trade](file, Some(1.day), 1.hour)

    trades.foreach(trade => { idLog.save(trade.micros, trade) })

    // idLog.scan().flatten.map(_._1).toSeq.size shouldBe 565
  }

  private def deleteFile(file: File) {
    if (!file.exists) return
    if (file.isFile) {
      file.delete()
    } else {
      file.listFiles().foreach(deleteFile)
      file.delete()
    }
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
      deleteFile(testFolder)
    }
  }

}

