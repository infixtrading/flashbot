package console
import java.io.File

import akka.NotUsed
import akka.actor.{ActorPath, ActorSystem, RelativeActorPath, RootActorPath}
import akka.cluster.Cluster
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Source}
import com.infixtrading.flashbot.client.FlashbotClient
import com.infixtrading.flashbot.core.{FlashbotConfig, Trade}
import com.infixtrading.flashbot.engine.{DataServer, TimeLog, TradingEngine}
import com.infixtrading.flashbot.engine.TimeLog.TimeLog
import com.infixtrading.flashbot.models.core.Order.{Buy, Down, Sell, Up}

import scala.concurrent.duration._

object Console {
  val nowMillis = 1543017219051L // A few minutes before midnight
  val nowMicros = nowMillis * 1000
  val MicrosPerMinute: Long = 60L * 1000000

  val trades: Seq[Trade] = (1 to 1440) map { i =>
    Trade(i.toString, nowMicros + i * MicrosPerMinute, i, i, if (i % 2 == 0) Up else Down)
  }

  def tradeSrc(implicit mat: ActorMaterializer): (UniqueKillSwitch, Source[Trade, NotUsed]) =
    Source(trades.toList)
      .throttle(1, 200 millis)
      .viaMat(KillSwitches.single)(Keep.right)
      .preMaterialize()

  def buildTradeLog: TimeLog[Trade] = {
    val file = new File("target/console")
    val timeLog = TimeLog[Trade](file, Some(7 days))
    timeLog
  }

  implicit var system: ActorSystem = _

  def run(configKey: String = "flashbot"): ActorSystem = {
    val config = FlashbotConfig.load(configKey)
    system = ActorSystem("flashbot-system", config.conf)
    val dataServerActor = system.actorOf(DataServer.props(config))
    val tradingEngineActor = system.actorOf(TradingEngine.props("trading-engine", config, dataServerActor))
    system
  }

  def connect(tradingEnginePath: String): FlashbotClient = {
    val config = FlashbotConfig.load()
    system = ActorSystem("flashbot-system", config.conf)
    val cluster = Cluster(system)
    val client = new FlashbotClient(RootActorPath(cluster.selfAddress) / "user" / tradingEnginePath)
    client
  }
}
