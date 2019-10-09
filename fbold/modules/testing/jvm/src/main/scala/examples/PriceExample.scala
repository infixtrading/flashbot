package examples

import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import flashbot.client.FlashbotClient
import flashbot.core._
import flashbot.models._
import flashbot.models.api._
import console.Console._
import flashbot.models.core._

object PriceExample extends App {
  val console = create(engineName = "example-engine")
  val client = console.connectLocal()

  val prices = client.prices("coinbase/btc_usd/trades", TimeRange(0), 1 hour)

  println(prices)

  globalSystem.terminate() onComplete { _ => System.exit(0) }
}
