package com.infixtrading.flashbot.engine
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.alpakka.slick.javadsl.SlickSession
import akka.stream.alpakka.slick.scaladsl.Slick
import akka.stream.scaladsl.{Keep, Sink}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.infixtrading.flashbot.core.{FlashbotConfig, MarketData, Trade}
import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, IngestConfig}
import com.infixtrading.flashbot.engine.DataServer.{DataSelection, DataStreamReq}
import com.typesafe.config.ConfigFactory
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

class DataServerSpec extends TestKit(ActorSystem("DataServerSpec",
    config = {
      val conf = ConfigFactory.load(classOf[TradingEngine].getClassLoader)
      val fbConf = conf.getConfig("flashbot")
      val finalConf =
        ConfigFactory.defaultApplication()
          .withFallback(fbConf)
          .withFallback(conf)
      finalConf
    }
  )) with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender {

  "DataServer" should {
    "ingest and serve data" in {
      implicit val timeout = Timeout(10 seconds)
      implicit val mat = ActorMaterializer()
      implicit val ec = system.dispatcher

      // Create data server actor.
      val conf = FlashbotConfig.load
      val dataserver = system.actorOf(Props(new DataServer(conf.db,
        // Ingests from a stream that is configured to send data for about 3 seconds.
        Map("bitfinex" -> DataSourceConfig("sources.TestDataSource",
          Some(Seq("btc_usd")), Some(Seq("trades")))),
        conf.exchanges,
        Some(IngestConfig(Seq("bitfinex/btc_usd/trades"), "1d")),
        useCluster = false
      )))

      // Ingest for 1 second with no subscriptions.
      Thread.sleep(2000)

      // Then subscribe to a path and get a data stream.
      val fut = dataserver ? DataStreamReq(DataSelection("bitfinex/btc_usd/trades", Some(0)))
      val rsp = Await.result(fut.mapTo[StreamResponse[MarketData[Trade]]], timeout.duration)
      val rspStream = rsp.toSource

      val mds = Await.result(rspStream.toMat(Sink.seq)(Keep.right).run, timeout.duration)
      val expectedIds = (1 to mds.size).map(_.toString)
      mds.map(_.data.id) shouldEqual expectedIds
    }

    "serve data from multiple data servers" in {
      // Use h2 jdbc src.

      // Create multiple data servers with different ingest configs.

      // 0s-1s: only one server is ingesting bitfinex/btc_usd/trades

      // 1s-2s: both servers are ingesting bitfinex/btc_usd/trades

      // 2s-3s: only the other server is ingesting bitfinex/btc_usd/trades

      // Should be able to request the entire dataset as one continuous stream.
    }
  }

}
