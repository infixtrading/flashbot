package examples

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import flashbot.client.FlashbotClient
import flashbot.core.{DataServer, FlashbotConfig, Trade, TradingEngine}
import io.prometheus.client.exporter.HTTPServer

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object MarketDataDashboard extends App {

  // Load config
  val config = FlashbotConfig.load("dashboard")

  // For graphing internal metrics
  var metricsServer: HTTPServer = new HTTPServer(9322)

  // Create the actor system and trading engine
  implicit val system = ActorSystem(config.systemName, config.conf)
  implicit val materializer = ActorMaterializer()

  // Create a data server and trading engine
  val dataServer = system.actorOf(DataServer.props(config))
  val engine = system.actorOf(TradingEngine.props(
    "example-engine", config, dataServer))

  // Create a FlashbotClient
  val client = new FlashbotClient(engine)

  // Gracefully shutdown the system and exit the program.
//  val term = Await.ready(system.terminate(), 5 seconds)
//  System.exit(if (term.value.get.isSuccess) 0 else 1)
}
