package examples

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import flashbot.client.FlashbotClient
import flashbot.util.time._
import flashbot.core._
import flashbot.config._
import flashbot.engine.{DataServer, TradingEngine}
import flashbot.models.core.OrderBook
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

object CoinbaseIngest extends App {

  implicit val config = FlashbotConfig.load().copy(
    ingest = IngestConfig(
//      enabled = Seq("coinbase/btc_usd/trades"),
//      enabled = Seq("coinbase/btc_usd/book"),
      enabled = Seq("coinbase/btc_usd/trades", "coinbase/btc_usd/book"),
//      enabled = Seq(),
      backfill = Seq("coinbase/btc_usd/trades"),
//      backfill = Seq(),
      retention = Seq()
    )
  )

  var metricsServer: HTTPServer = new HTTPServer(9322)

  implicit val system = ActorSystem("coinbase-system", config.conf)

  val dataServer = system.actorOf(DataServer.props(config))

  val engine = system.actorOf(TradingEngine.props("trading-engine", config, dataServer))

  val blockingEc: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(5))

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat = ActorMaterializer()
  val client = new FlashbotClient(engine)

//  val marketDataLatency = Summary.build("client_marketdata_ms",
//    "Client market data request latency in millis").register()
//  val backtestLatency = Summary.build("client_price_ms",
//    "Client price request latency in millis").register()

//  Await.result(Future {
//    Thread.sleep(5000)
//    while (true) {
////      val timer = marketDataLatency.startTimer()
//      client.pollingMarketDataAsync[OrderBook]("coinbase/btc_usd/book")
//          .onComplete {
//            case Success(value) =>
//              println(s"SUCCESS")
//              value.runForeach(x => println(x))
//            case Failure(err) =>
//              println(s"ERROR: $err")
//              throw err
//          }
//
////          .andThen { case _ => timer.observeDuration() }
//      Thread.sleep(5000)
//    }
//  }(blockingEc), 1 hour)
//
//  Future {
//    Thread.sleep(6500)
//    while (true) {
//      val timer = backtestLatency.startTimer()
//      client.pricesAsync("coinbase/btc_usd/trades", TimeRange(0, Long.MaxValue), 5 minutes)
//        .andThen { case _ => timer.observeDuration() }
//      Thread.sleep(1000)
//    }
//  }(blockingEc)


//  Thread.sleep(1000 * 60)

//  Await.ready(for {
//    _ <- system.terminate()
//    _ <- TestDB.dropTestDB()
//  } yield Unit, 10 seconds)

//  System.exit(0)
}
