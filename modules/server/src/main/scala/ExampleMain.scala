import java.io.File

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.infixtrading.flashbot.core.{FlashbotConfig, Paper}
import com.infixtrading.flashbot.engine.{DataServer, TradingEngine}
import com.infixtrading.flashbot.models.api.{ConfigureBot, Ping}
import com.infixtrading.flashbot.models.core.Portfolio
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.duration._
import scala.concurrent.Await
object ExampleMain extends App {

  val system = ActorSystem("my-system", FlashbotConfig.load.akka)

  val engine = system.actorOf(TradingEngine.props("my-engine"))

  implicit val timeout = Timeout(5 seconds)
  println(Await.result(engine ? Ping, timeout.duration))

//  println(Await.result(engine ? ConfigureBot(
//    id = "my-scanner",
//    strategyKey = "scanner",
//    strategyParams = "{}",
//    mode = Paper(5 seconds),
//    ttl = None,
//    initialPortfolio = Portfolio.empty
//  ), timeout.duration))

  system.terminate() onComplete (_ => System.exit(0))
}
