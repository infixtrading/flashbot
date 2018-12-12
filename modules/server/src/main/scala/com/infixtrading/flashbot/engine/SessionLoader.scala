package com.infixtrading.flashbot.engine

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.pattern.ask
import akka.util.Timeout
import io.circe.Json
import com.infixtrading.flashbot.core.DataSource.{Bundle, DataClusterIndex, DataSourceIndex}
import com.infixtrading.flashbot.core.Exchange
import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, ExchangeConfig}
import com.infixtrading.flashbot.engine.DataServer.ClusterDataIndexReq
import com.infixtrading.flashbot.engine.TradingEngine.EngineError

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class SessionLoader(exchangeConfigs: Map[String, ExchangeConfig])
                   (implicit ec: ExecutionContext) {
  implicit val timeout = Timeout(10 seconds)

  def index: Future[DataSourceIndex] = {
    (Control.dataServer.get ? ClusterDataIndexReq).collect {
      case idx: DataClusterIndex =>
        println(idx)
        idx.slices
    }
  }

  def exchanges: Set[String] = exchangeConfigs.keySet

  protected[engine] def loadNewExchange(config: ExchangeConfig)
                                       (implicit system: ActorSystem,
                                        mat: ActorMaterializer): Try[Exchange] =
    try {
      Success(getClass.getClassLoader
        .loadClass(config.`class`)
        .asSubclass(classOf[Exchange])
        .getConstructor(classOf[Json], classOf[ActorSystem], classOf[ActorMaterializer])
        .newInstance(config.params, system, mat)
      )
    } catch {
      case err: ClassNotFoundException =>
        Failure(EngineError("Exchange class not found: " + config.`class`, Some(err)))
      case err: ClassCastException =>
        Failure(EngineError(s"Class ${config.`class`} must be a " +
          s"subclass of io.flashbook.core.Exchange", Some(err)))
    }

  protected[engine] def loadNewStrategy(className: String): Try[Strategy] =
    try {
      Success(getClass.getClassLoader
        .loadClass(className)
        .asSubclass(classOf[Strategy])
        .newInstance())
    } catch {
      case err: ClassNotFoundException =>
        Failure(EngineError(s"Strategy class not found: $className", Some(err)))

      case err: ClassCastException =>
        Failure(EngineError(s"Class $className must be a " +
          s"subclass of io.flashbook.core.Strategy", Some(err)))
      case err => Failure(err)
    }
}
