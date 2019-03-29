package flashbot.util.time

import akka.util.Timeout

object FlashbotTimeout {
  val default: Timeout = Timeout(60 seconds)
}
