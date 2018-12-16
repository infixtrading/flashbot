package com.infixtrading.flashbot.engine

import java.io.File
import java.time.Instant

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.infixtrading.flashbot.models.api.{BacktestQuery, Ping, Pong, ReportResponse}
import com.infixtrading.flashbot.util.files.rmRf
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest._
import akka.actor.{ActorSystem, PoisonPill}
import akka.testkit.{ImplicitSender, TestKit}
import com.infixtrading.flashbot.core.FlashbotConfig
import com.infixtrading.flashbot.models.core._
import com.infixtrading.flashbot.report.Report
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import io.circe._
import io.circe.Printer
import io.circe.literal._
import io.circe.syntax._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalacheck.Properties
import org.scalacheck.Prop.forAll
import strategies.LookaheadParams

class TradingEngineSpec
  extends TestKit(ActorSystem("TradingEngineSpec", ConfigFactory.load()))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  var testFolder: File = _
  implicit val timeout = Timeout(5 seconds)

  "TradingEngine" should {
    "respond to a ping" in {

      val fbConfig = FlashbotConfig.load match {
        case Right(v) => v
        case Left(err) => fail(err)
      }

      val dataServer = system.actorOf(Props(new DataServer(
        testFolder,
        fbConfig.sources,
        fbConfig.exchanges,
        None,
        useCluster = false
      )))

      val engine = system.actorOf(Props(new TradingEngine(
        "test",
        fbConfig.strategies,
        fbConfig.exchanges,
        fbConfig.bots.configs,
        dataServer
      )))

      val result = Await.result(engine ? Ping, 5 seconds)
      result match {
        case Pong(micros) =>
          println(Instant.ofEpochMilli(micros/1000))
        case _ =>
          fail("should respond with a Pong")
      }
    }

    "be profitable when using lookahead" in {
      val fbConfig = FlashbotConfig.load match {
        case Right(v) => v
        case Left(err) => fail(err)
      }

      val now = Instant.now()

      val dataServer = system.actorOf(Props(
        new DataServer(testFolder, fbConfig.sources, fbConfig.exchanges, None,
          useCluster = false)), "data-server")

      val engine = system.actorOf(Props(
        new TradingEngine("test2", fbConfig.strategies, fbConfig.exchanges, Map.empty,
          dataServer)), "trading-engine-2")

      val params = LookaheadParams(Market("bitfinex/eth_usd"), sabotage = false)

      val report = Await.result((engine ? BacktestQuery(
        "lookahead",
        params.asJson.pretty(Printer.noSpaces),
        TimeRange.build(now, 60 days),
        Portfolio(
          Map(Account("bitfinex/eth") -> 8.0, Account("bitfinex/usd") -> 800),
          Map.empty
        ).asJson.pretty(Printer.noSpaces),
        Some(1 hour),
        None
      )).map {
        case ReportResponse(report: Report) => report
      }, timeout.duration)

      // We should have 60 days worth of report data.
      val timeSeriesBarCount = 60 * 24
      report.error shouldBe None
//      report.timeSeries("returns").size shouldBe timeSeriesBarCount

      // There shouldn't be a single period of negative returns when the algo is cheating.
    }

//    "lose money when using lookahead to self sabatoge" in {
//
//    }
  }
//
//  "TradingEngine" should "start a bot" in {
//    val system = ActorSystem("test")
//
//    val dataServer = system.actorOf(Props(
//      new DataServer(testFolder, Map.empty, Map.empty, None, useCluster = false)))
//
//    val engine = system.actorOf(Props(
//      new TradingEngine("test", Map.empty, Map.empty, Map.empty, dataServer)))
//
////    (engine ? )
//
//    system.terminate()
//
//  }
//
//  "TradingEngine" should "recover bots after a restart" in {
//  }
//
//  override def withFixture(test: NoArgTest) = {
//    val tempFolder = System.getProperty("java.io.tmpdir")
//    var folder: File = null
//    do {
//      folder = new File(tempFolder, "scalatest-" + System.nanoTime)
//    } while (! folder.mkdir())
//    testFolder = folder
//    try {
//      super.withFixture(test)
//    } finally {
//      rmRf(testFolder)
//    }
//  }
}
