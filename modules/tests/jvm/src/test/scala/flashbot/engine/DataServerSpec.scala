package flashbot.engine
import java.time.Instant

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.alpakka.slick.javadsl.SlickSession
import akka.stream.alpakka.slick.scaladsl.Slick
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import flashbot.models.api.{DataSelection, DataStreamReq}
import com.typesafe.config.ConfigFactory
import flashbot.config._
import flashbot.core.{MarketData, Trade}
import flashbot.models.core.Ladder
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import sources.TestBackfillDataSource
import util.TestDB

import scala.concurrent.Await
import scala.concurrent.duration._

class DataServerSpec extends WordSpecLike with Matchers with Eventually {

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
      implicit val fbConfig = FlashbotConfig.load()
      val dataserver = system.actorOf(Props(new DataServer(fbConfig.db,
        // Ingests from a stream that is configured to send data for about 3 seconds.
        Map("bitfinex" -> DataSourceConfig("sources.TestDataSource",
          Some(Seq("btc_usd")), Some(Seq("trades")))),
        fbConfig.exchanges,
        IngestConfig(Seq("bitfinex/btc_usd/trades"), Seq(), Seq(Seq()))
      )))

      // Ingest for 2 second with no subscriptions.
      Thread.sleep(2000)

      // Then subscribe to a path and series a data stream.
      val fut = dataserver ? DataStreamReq(DataSelection("bitfinex/btc_usd/trades", Some(0)))
      val rsp = Await.result(fut.mapTo[StreamResponse[MarketData[Trade]]], timeout.duration)
      val rspStream = rsp.toSource

      val mds = Await.result(rspStream.toMat(Sink.seq)(Keep.right).run, timeout.duration)
      val expectedIds = (1 to 120).map(_.toString)
      mds.map(_.data.id) shouldEqual expectedIds

      Await.ready(for {
        _ <- system.terminate()
        _ <- TestDB.dropTestDB()
      } yield Unit, 10 seconds)
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
      implicit val fbConfig = FlashbotConfig.load()
      val dataserver = system.actorOf(Props(new DataServer(fbConfig.db,
        Map("bitfinex" -> DataSourceConfig("sources.TestLadderDataSource",
          Some(Seq("btc_usd")), Some(Seq("ladder")))),
        fbConfig.exchanges,
        IngestConfig(Seq("bitfinex/btc_usd/ladder"), Seq(), Seq(Seq()))
      )))

      // Ingest for 2 second with no subscriptions.
      Thread.sleep(2000)

      // Then subscribe to a path and series a data stream.
      val fut = dataserver ? DataStreamReq(DataSelection("bitfinex/btc_usd/ladder", Some(0)))
      val rsp = Await.result(fut.mapTo[StreamResponse[MarketData[Ladder]]], timeout.duration)
      val rspStream = rsp.toSource

      Await.ready(for {
        _ <- system.terminate()
        _ <- TestDB.dropTestDB()
      } yield Unit, 10 seconds)
    }

    /**
      * This test uses a DataSource that splits a hard coded seq of trades in two parts.
      * The first part will be backfilled and the second part will be streamed to ingest.
      * We should be able to request the entire uninterrupted trade stream in its original
      * form when ingest and backfill is done.
      */
    "ingest and backfill trades" in {

      implicit val config = FlashbotConfig.load().copy(
        ingest = IngestConfig(
          enabled = Seq("bitfinex/btc_usd/trades"),
          backfill = Seq("bitfinex/btc_usd/trades"),
          retention = Seq(Seq())
        ),
        sources = Map(
          "bitfinex" -> DataSourceConfig("sources.TestBackfillDataSource",
            Some(Seq("btc_usd")), Some(Seq("trades"))))
      )

      implicit val system = ActorSystem("system1", config.conf)
      val dataServer = system.actorOf(DataServer.props(config))

      implicit val timeout = Timeout(1 minute)
      implicit val mat = ActorMaterializer()
      implicit val ec = system.dispatcher

      def fetchTrades() = {
        val fut = dataServer ? DataStreamReq(DataSelection("bitfinex/btc_usd/trades", Some(0), Some(Long.MaxValue)))
        val rsp = Await.result(fut.mapTo[StreamResponse[MarketData[Trade]]], timeout.duration)
        val src = rsp.toSource
        Await.result(src.toMat(Sink.seq)(Keep.right).run, timeout.duration)
      }

      implicit val patienceConfig =
        PatienceConfig(timeout = scaled(Span(8, Seconds)), interval = scaled(Span(500, Millis)))
      eventually {
        val fetched = fetchTrades()
        fetched.map(_.data) shouldEqual TestBackfillDataSource.allTrades
      }

      Await.ready(for {
        _ <- system.terminate()
        _ <- TestDB.dropTestDB()
      } yield Unit, 10 seconds)
    }
  }
}
