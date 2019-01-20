package com.infixtrading.flashbot.engine

import akka.actor.ActorSystem
import com.infixtrading.flashbot.core.FlashbotConfig
import com.infixtrading.flashbot.core.FlashbotConfig.IngestConfig
import org.scalatest.Matchers
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import util.TestDB

import scala.concurrent.Await
import scala.concurrent.duration._

class CoinbaseIngestTest extends WordSpecLike with Matchers {
  "Coinbase" should {
    "ingest trades" in {
      implicit val config = FlashbotConfig.load.copy(
        ingest = IngestConfig(
          enabled = Seq("coinbase/btc_usd/trades"),
          backfill = Seq(),
          retention = Seq()
        )
      )
      implicit val system = ActorSystem("coinbase-system", config.akka)
      implicit val ec = system.dispatcher

      system.actorOf(DataServer.props(config))

      Thread.sleep(1000 * 60)

      Await.ready(for {
        _ <- system.terminate()
        _ <- TestDB.dropTestDB()
      } yield Unit, 10 seconds)
    }
  }
}
