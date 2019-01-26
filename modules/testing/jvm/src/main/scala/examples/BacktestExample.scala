package examples

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import com.infixtrading.flashbot.client.FlashbotClient
import com.infixtrading.flashbot.core.{FlashbotConfig, Trade}
import com.infixtrading.flashbot.engine.TradingEngine

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

object BacktestExample extends App {
  // Load config
  val config = FlashbotConfig.load()

  // Create the actor system and trading engine
  implicit val system = ActorSystem("example-system", config.conf)
  implicit val materializer = ActorMaterializer()
  val engine = system.actorOf(TradingEngine.props("example-engine"))

  // Create a FlashbotClient
  val client = new FlashbotClient(engine)

  // Gracefully shutdown the system and exit the program.
  val term = Await.ready(system.terminate(), 5 seconds)
  System.exit(if (term.value.get.isSuccess) 0 else 1)
}
