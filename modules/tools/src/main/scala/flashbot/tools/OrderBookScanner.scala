package flashbot.tools

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import flashbot.core.DataType.LadderType
import flashbot.core.FlashbotConfig
import flashbot.sources.BitMEXMarketDataSource
import flashbot.util.stream.buildMaterializer
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.stream.scaladsl.Source
import flashbot.models.Ladder
import flashbot.util.TableUtil

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.language.postfixOps

object OrderBookScanner extends App {
  implicit val config: FlashbotConfig = FlashbotConfig.load()
  implicit val system: ActorSystem = ActorSystem(config.systemName, config.conf)
  implicit val mat: ActorMaterializer = buildMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(10 seconds)

  val bitmex = new BitMEXMarketDataSource()

  class ScannerActor extends Actor {
    override def receive: Receive = {
      case "scan" =>
        bitmex.ingest("XBTUSD", LadderType(Some(25))) pipeTo sender
    }
  }

  val scanner = system.actorOf(Props(new ScannerActor()))
  val src = Await.result((scanner ? "scan").mapTo[Source[(Long, Ladder), NotUsed]], timeout.duration)

  src.groupedWithin(500, 50 millis)
    .map(_.lastOption)
    .collect {
      case Some(x) => x
    } runForeach {
      case (micros, ladder) =>
        TableUtil.renderLadder(ladder)
    }
}
