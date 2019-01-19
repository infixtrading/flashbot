package com.infixtrading.flashbot.engine
import java.time.Instant

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.alpakka.slick.javadsl.SlickSession
import akka.stream.alpakka.slick.scaladsl.Slick
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.infixtrading.flashbot.core.{FlashbotConfig, MarketData, OrderBookTap, Trade}
import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, IngestConfig}
import com.infixtrading.flashbot.engine.DataServer.{DataSelection, DataStreamReq}
import com.infixtrading.flashbot.models.core.Ladder
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

class DataServerSpec extends WordSpecLike with Matchers {

  "DataServer" should {
    "ingest and serve trades" in {
      val conf = ConfigFactory.load(classOf[TradingEngine].getClassLoader)
      val fbConf = conf.getConfig("flashbot")
      implicit val system = ActorSystem("System1", ConfigFactory.defaultApplication()
          .withFallback(fbConf)
          .withFallback(conf))

      implicit val timeout = Timeout(10 seconds)
      implicit val mat = ActorMaterializer()
      implicit val ec = system.dispatcher

      // Create data server actor.
      val fbConfig = FlashbotConfig.load
      val dataserver = system.actorOf(Props(new DataServer(fbConfig.db,
        // Ingests from a stream that is configured to send data for about 3 seconds.
        Map("bitfinex" -> DataSourceConfig("sources.TestDataSource",
          Some(Seq("btc_usd")), Some(Seq("trades")))),
        fbConfig.exchanges,
        IngestConfig(Seq("bitfinex/btc_usd/trades"), "1d"),
        useCluster = false
      )))

      // Ingest for 2 second with no subscriptions.
      Thread.sleep(2000)

      // Then subscribe to a path and get a data stream.
      val fut = dataserver ? DataStreamReq(DataSelection("bitfinex/btc_usd/trades", Some(0)))
      val rsp = Await.result(fut.mapTo[StreamResponse[MarketData[Trade]]], timeout.duration)
      val rspStream = rsp.toSource

      val mds = Await.result(rspStream.toMat(Sink.seq)(Keep.right).run, timeout.duration)
      val expectedIds = (1 to 120).map(_.toString)
      mds.map(_.data.id) shouldEqual expectedIds

      Await.ready(system.terminate(), 10 seconds)
    }

    "ingest and serve ladders" in {
      val conf = ConfigFactory.load(classOf[TradingEngine].getClassLoader)
      val fbConf = conf.getConfig("flashbot")
      implicit val system = ActorSystem("System1", ConfigFactory.defaultApplication()
          .withFallback(fbConf)
          .withFallback(conf))

      implicit val timeout = Timeout(1 minute)
      implicit val mat = ActorMaterializer()
      implicit val ec = system.dispatcher

      // Create data server actor.
      val fbConfig = FlashbotConfig.load
      val dataserver = system.actorOf(Props(new DataServer(fbConfig.db,
        Map("bitfinex" -> DataSourceConfig("sources.TestLadderDataSource",
          Some(Seq("btc_usd")), Some(Seq("ladder")))),
        fbConfig.exchanges,
        IngestConfig(Seq("bitfinex/btc_usd/ladder"), "1d"),
        useCluster = false
      )))

      // Ingest for 2 second with no subscriptions.
      Thread.sleep(2000)

      // Then subscribe to a path and get a data stream.
      val fut = dataserver ? DataStreamReq(DataSelection("bitfinex/btc_usd/ladder", Some(0)))
      val rsp = Await.result(fut.mapTo[StreamResponse[MarketData[Ladder]]], timeout.duration)
      val rspStream = rsp.toSource

//      Await.result(rspStream.runForeach(println("foo", _)), timeout.duration)
      Await.ready(system.terminate(), 10 seconds)
    }
  }
}
