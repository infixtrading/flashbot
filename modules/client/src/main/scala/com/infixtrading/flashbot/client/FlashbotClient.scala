package com.infixtrading.flashbot.client

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import com.infixtrading.flashbot.models.api.GetDataServer

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * A this wrapper class to interact with a Flashbot cluster or a standalone Flashbot engine.
  * When in cluster mode the client can attach to a trading engine within the cluster.
  * When in standalone mode, the client is always attached to the supplied engine.
  * The constructor requires the ActorRef of a TradingEngine.
  * Not thread safe!
  */
class FlashbotClient(val engine: ActorRef) {

  implicit val timeout: Timeout = Timeout(10 seconds)

  def getDataServerAsync: Future[ActorRef] = (engine ? GetDataServer).mapTo[ActorRef]
  def getDataServer: ActorRef = Await.result(getDataServerAsync, timeout.duration)



}
