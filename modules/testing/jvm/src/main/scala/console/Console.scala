package console
import java.io.File

import akka.NotUsed
import akka.actor.{Actor, ActorPath, ActorRef, ActorSystem, RelativeActorPath, RootActorPath}
import akka.cluster.Cluster
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, Source}
import flashbot.server.TimeLog
import flashbot.server.TimeLog.TimeLog
import flashbot.client.FlashbotClient
import flashbot.core._
import flashbot.models.core.Order._

import scala.concurrent.duration._
import scala.concurrent.blocking

object Console {
  var instanceOpt: Option[Console] = None

  implicit def globalSystem: ActorSystem = instanceOpt.get.system
  implicit def globalMat: ActorMaterializer = instanceOpt.get.mat

  def create(configKey: String = "application",
             engineName: String = "console-engine"): Console = {
    instanceOpt = Some(new Console(configKey, engineName))
    instanceOpt.get
  }
}

class Console(val configKey: String,
              val engineName: String) {
  implicit val config = FlashbotConfig.load(configKey)
  implicit val system = ActorSystem(config.systemName, config.conf)
  implicit val mat = ActorMaterializer()
  lazy val cluster = Cluster(system)

  var dataServer: Option[ActorRef] = None
  var engine: Option[ActorRef] = None

  def startDataServer(): ActorRef = {
    if (dataServer.isDefined) {
      println("Data server already started")
      dataServer.get
    } else {
      val ref = system.actorOf(DataServer.props(config))
      dataServer = Some(ref)
      ref
    }
  }

  def startEngine(): ActorRef = {
    if (engine.isDefined) {
      println("Engine already started")
      engine.get
    } else {
      val ref = if (dataServer.isDefined)
        system.actorOf(TradingEngine.props(engineName, config, dataServer.get), engineName)
      else
        system.actorOf(TradingEngine.props(engineName, config), engineName)
      engine = Some(ref)
      ref
    }
  }

  def connectLocal(): FlashbotClient = new FlashbotClient(startEngine())

  def connectRemote(): FlashbotClient =
    new FlashbotClient(RootActorPath(cluster.selfAddress) / "user" / engineName)
}
