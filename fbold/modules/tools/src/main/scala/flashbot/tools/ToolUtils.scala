package flashbot.tools

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import flashbot.core.FlashbotConfig
import flashbot.util.stream.buildMaterializer

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps

object ToolUtils {

  trait SimpleFlashbotSetup {
    implicit val config: FlashbotConfig = FlashbotConfig.load()
    implicit val system: ActorSystem = ActorSystem(config.systemName, config.conf)
    implicit val mat: ActorMaterializer = buildMaterializer()
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val timeout: Timeout = Timeout(10 seconds)
  }

}
