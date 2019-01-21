package examples

import akka.actor.ActorSystem
import com.infixtrading.flashbot.core.FlashbotConfig
import com.infixtrading.flashbot.core.FlashbotConfig.IngestConfig
import com.infixtrading.flashbot.engine.{DataServer, TradingEngine}
import util.TestDB

import scala.concurrent.Await
import scala.concurrent.duration._

object CoinbaseIngest extends App {
  implicit val config = FlashbotConfig.load.copy(
    ingest = IngestConfig(
      enabled = Seq("coinbase/btc_usd/trades"),
      backfill = Seq("coinbase/btc_usd/trades"),
      retention = Seq()
    )
  )
  implicit val system = ActorSystem("coinbase-system", config.akka)
//  implicit val ec = system.dispatcher

  val dataServer = system.actorOf(DataServer.props(config))

  val engine = system.actorOf(TradingEngine.props("trading-engine", config, dataServer))

//  Thread.sleep(1000 * 60)

//  Await.ready(for {
//    _ <- system.terminate()
//    _ <- TestDB.dropTestDB()
//  } yield Unit, 10 seconds)

//  System.exit(0)
}
