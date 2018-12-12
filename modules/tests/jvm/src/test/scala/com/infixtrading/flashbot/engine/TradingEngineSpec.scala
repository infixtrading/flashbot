package com.infixtrading.flashbot.engine

import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.infixtrading.flashbot.engine.TradingEngine.Ping
import com.infixtrading.flashbot.engine.TradingEngine.Pong
import com.infixtrading.flashbot.util.files.rmRf
import org.scalatest._

import scala.concurrent.Await
import scala.concurrent.duration._

class TradingEngineSpec extends FlatSpec with Matchers  {

  var testFolder: File = _
  implicit val timeout = Timeout(5 seconds)

  "TradingEngine" should "start in local mode" in {
    val system = ActorSystem("test")

    val dataServer = system.actorOf(Props(
      new DataServer(testFolder, Map.empty, Map.empty, None, useCluster = false)))

    val engine = system.actorOf(Props(
      new TradingEngine(Map.empty, Map.empty, Map.empty, dataServer)))

    val result = Await.result(engine ? Ping, 5 seconds)
    result shouldEqual Pong

    system.terminate()
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
