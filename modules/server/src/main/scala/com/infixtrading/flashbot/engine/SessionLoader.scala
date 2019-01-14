package com.infixtrading.flashbot.engine

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, Materializer}
import akka.pattern.ask
import akka.util.Timeout
import io.circe.Json
import com.infixtrading.flashbot.core.DataSource.{Bundle, DataSourceIndex}
import com.infixtrading.flashbot.core.Exchange
import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, ExchangeConfig}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class SessionLoader(getExchangeConfigs: () => Map[String, ExchangeConfig], dataServer: ActorRef)
                   (implicit val ec: ExecutionContext, val mat: Materializer) {
  implicit val timeout = Timeout(10 seconds)

  def exchanges: Set[String] = getExchangeConfigs().keySet

  protected[engine] def loadNewExchange(name: String)
                                       (implicit system: ActorSystem,
                                        mat: ActorMaterializer): Try[Exchange] = {
    val config = getExchangeConfigs().get(name)
    if (config.isEmpty) {
      return Failure(new RuntimeException(s"Exchange $name not found"))
    }

    try {
      Success(
        getClass.getClassLoader
          .loadClass(config.get.`class`)
          .asSubclass(classOf[Exchange])
          .getConstructor(classOf[ActorSystem], classOf[ActorMaterializer])
          .newInstance(system, mat).withParams(config.get.params))
    } catch {
      case err: ClassNotFoundException =>
        Failure(new RuntimeException("Exchange class not found: " + config.get.`class`, err))
      case err: ClassCastException =>
        Failure(
          new RuntimeException(s"Class ${config.get.`class`} must be a " +
                        s"subclass of com.infixtrading.flashbot.core.Exchange", err))
    }
  }

  protected[engine] def loadNewStrategy(className: String): Try[Strategy] =
    try {
      Success(getClass.getClassLoader
        .loadClass(className)
        .asSubclass(classOf[Strategy])
        .newInstance())
    } catch {
      case err: ClassNotFoundException =>
        Failure(new RuntimeException(s"Strategy class not found: $className", err))

      case err: ClassCastException =>
        Failure(new RuntimeException(s"Class $className must be a " +
          s"subclass of com.infixtrading.flashbot.core.Strategy", err))
      case err => Failure(err)
    }
}
