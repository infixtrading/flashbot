package examples

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import com.infixtrading.flashbot.client.FlashbotClient
import com.infixtrading.flashbot.core.FlashbotConfig
import com.infixtrading.flashbot.util.time._
import com.infixtrading.flashbot.core.FlashbotConfig.IngestConfig
import com.infixtrading.flashbot.engine.{DataServer, TradingEngine}
import com.infixtrading.flashbot.models.core.TimeRange
import io.prometheus.client.Summary
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

object CoinbaseIngest extends App {


  implicit val config = FlashbotConfig.load.copy(
    ingest = IngestConfig(
      enabled = Seq("coinbase/btc_usd/trades"),
      backfill = Seq("coinbase/btc_usd/trades"),
      retention = Seq()
    )
  )

  var metricsServer: HTTPServer = new HTTPServer(9322)

  implicit val system = ActorSystem("coinbase-system", config.akka)

  val dataServer = system.actorOf(DataServer.props(config))

  val engine = system.actorOf(TradingEngine.props("trading-engine", config, dataServer))

  val blockingEc: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(5))

  implicit val ec: ExecutionContext = system.dispatcher
  val client = new FlashbotClient(engine)(ec)

  val marketDataLatency = Summary.build("client_marketdata_ms",
    "Client market data request latency in millis").register()
  val backtestLatency = Summary.build("client_price_ms",
    "Client price request latency in millis").register()

  Future {
    Thread.sleep(5000)
    while (true) {
      val timer = marketDataLatency.startTimer()
      client.historicalMarketDataAsync("coinbase/btc_usd/trades", Some(0.microsToInstant))
          .andThen { case _ => timer.observeDuration() }
      Thread.sleep(1000)
    }
  }(blockingEc)

  Future {
    Thread.sleep(6500)
    while (true) {
      val timer = backtestLatency.startTimer()
      client.pricesAsync("coinbase/btc_usd/trades", TimeRange(0, Long.MaxValue), 5 minutes)
        .andThen { case _ => timer.observeDuration() }
      Thread.sleep(1000)
    }
  }(blockingEc)


//  Thread.sleep(1000 * 60)

//  Await.ready(for {
//    _ <- system.terminate()
//    _ <- TestDB.dropTestDB()
//  } yield Unit, 10 seconds)

//  System.exit(0)
}
