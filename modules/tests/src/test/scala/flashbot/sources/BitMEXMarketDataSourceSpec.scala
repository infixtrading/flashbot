package flashbot.sources

import akka.actor.{Actor, ActorContext, ActorSystem, Props}
import akka.stream.ActorMaterializer
import flashbot.core.DataType.LadderType
import flashbot.core.FlashbotConfig
import flashbot.models.Ladder
import flashbot.server.DataSourceActor
import org.scalatest.{FlatSpec, Matchers}
import flashbot.util.stream.buildMaterializer
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.util.Timeout
import util.TestDB

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor}
import scala.language.postfixOps

class BitMEXMarketDataSourceSpec extends FlatSpec with Matchers {

  "BitMEXMarketDataSource" should "ingest order books depths" in {
    implicit val config: FlashbotConfig = FlashbotConfig.load()
    implicit val system: ActorSystem = ActorSystem(config.systemName, config.conf)
    implicit val mat: ActorMaterializer = buildMaterializer()
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(10 seconds)

    val bitmex = new BitMEXMarketDataSource()

    class TestActor extends Actor {
      val srcFut = bitmex.ingest("XBTUSD", LadderType(Some(25)))

      override def receive = {
        case "test" =>
          var count = 0
          (for {
            src <- srcFut
            _ <- src.take(200).runForeach {
              case (micros, ladder) =>
                if (count > 0) println(ladder.lastUpdate.get)
                count = count + 1
            }
          } yield count) pipeTo sender
      }
    }

    val actor = system.actorOf(Props(new TestActor))
    val result = Await.result((actor ? "test").mapTo[Integer], timeout.duration)

    result shouldEqual 200

    Await.ready(for {
      _ <- system.terminate()
      _ <- TestDB.dropTestDB()
    } yield Unit, 10 seconds)
  }
}
