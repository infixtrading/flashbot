package com.infixtrading.flashbot.client.scala.client

import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.infixtrading.flashbot.core.FlashbotConfig.BotConfig
import com.infixtrading.flashbot.engine.NetworkSource
import com.infixtrading.flashbot.models.api._
import com.infixtrading.flashbot.report.Report

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

class FlashbotClient(engine: ActorRef) {

  implicit val timeout: Timeout = Timeout(10.seconds)

  def configureBotAsync(id: String, config: BotConfig) = req[Done](ConfigureBot(id, config))
  def configureBot(id: String, config: BotConfig): Unit = await(configureBotAsync(id, config))

  def botStatusAsync(id: String) = req[BotStatus](BotStatusQuery(id))
  def botStatus(id: String) = await(botStatusAsync(id))

  def enableBotAsync(id: String) = req[Done](EnableBot(id))
  def enableBot(id: String): Unit = await(enableBotAsync(id))

  def disableBotAsync(id: String) = req[Done](DisableBot(id))
  def disableBot(id: String): Unit = await(disableBotAsync(id))

  def botHeartbeatAsync(id: String) = req[Done](BotHeartbeat(id))
  def botHeartbeat(id: String): Unit = await(botHeartbeatAsync(id))

  def subscribeToReportAsync(id: String) =
    req[NetworkSource[Report]](SubscribeToReport(id)).map(_.toSource)
  def subscribeToReport(id: String) = await(subscribeToReportAsync(id))

  def pingAsync = req[Pong](Ping)
  def ping: Pong = await[Pong](pingAsync)

  private def req[T](query: Any)(implicit tag: ClassTag[T]): Future[T] = (engine ? query).mapTo[T]
  private def await[T](fut: Future[T]): T = Await.result[T](fut, timeout.duration)

}
