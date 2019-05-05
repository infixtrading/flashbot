package flashbot.util.time

import akka.util.Timeout

import scala.concurrent.duration._
import scala.language.postfixOps

object FlashbotTimeout {
  val default: Timeout = Timeout(60 seconds)
}
