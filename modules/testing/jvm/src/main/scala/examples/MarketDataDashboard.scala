package examples

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import flashbot.client.FlashbotClient
import flashbot.core.{FlashbotConfig, Trade}
import flashbot.engine.TradingEngine

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object MarketDataDashboard extends App {
  // Load config
  val config = FlashbotConfig.load()

  // Create the actor system and trading engine
  implicit val system = ActorSystem("example-system", config.conf)
  implicit val materializer = ActorMaterializer()
  val engine = system.actorOf(TradingEngine.props("example-engine"))

  // Create a FlashbotClient
  val client = new FlashbotClient(engine)

  // Poll and print trades for 10 seconds.
  Await.ready(
    client.pollingMarketData[Trade]("coinbase/btc_usd/trades")
      .takeWithin(10 seconds)
      .runForeach(println),
    10 seconds)

  // Gracefully shutdown the system and exit the program.
  val term = Await.ready(system.terminate(), 5 seconds)
  System.exit(if (term.value.get.isSuccess) 0 else 1)
}
