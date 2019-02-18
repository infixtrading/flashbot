package flashbot.core

import akka.actor.{ActorRef, ActorSystem}
import akka.stream.Materializer
import akka.util.Timeout
import akka.pattern.ask
import flashbot.core.FlashbotConfig.ExchangeConfig
import flashbot.models.api.MarketDataIndexQuery
import flashbot.models.core.{DataPath, Market}
import flashbot.server.StrategyInfo

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class EngineLoader(getExchangeConfigs: () => Map[String, ExchangeConfig],
                   dataServer: ActorRef, strategyClassNames: Map[String, String])
                  (implicit val ec: ExecutionContext, val mat: Materializer) {
  implicit val timeout = Timeout(10 seconds)

  def exchanges: Set[String] = getExchangeConfigs().keySet

  def markets: Future[Set[Market]] = for {
    index: Map[Long, DataPath[Any]] <-
      (dataServer ? MarketDataIndexQuery).mapTo[Map[Long, DataPath[Any]]]
  } yield index.values.map(_.market).toSet

  protected[flashbot] def loadNewExchange(name: String)
                                         (implicit system: ActorSystem,
                                          mat: Materializer): Try[Exchange] = {

    val config = getExchangeConfigs().get(name)
    if (config.isEmpty) {
      return Failure(new RuntimeException(s"Exchange $name not found"))
    }

    try {
      Success(
        getClass.getClassLoader
          .loadClass(config.get.`class`)
          .asSubclass(classOf[Exchange])
          .getConstructor(classOf[ActorSystem], classOf[Materializer])
          .newInstance(system, mat).withParams(config.get.params))
    } catch {
      case err: ClassNotFoundException =>
        Failure(new RuntimeException("Exchange class not found: " + config.get.`class`, err))
      case err: ClassCastException =>
        Failure(
          new RuntimeException(s"Class ${config.get.`class`} must be a " +
                        s"subclass of flashbot.core.Exchange", err))
    }
  }

  protected[flashbot] def loadNewStrategy[P](className: String): Try[Strategy[P]] =
    try {
      val clazz = getClass.getClassLoader.loadClass(className).asSubclass(classOf[Strategy[P]])
      Success(clazz.newInstance())
    } catch {
      case err: ClassNotFoundException =>
        Failure(new RuntimeException(s"Strategy class not found: $className", err))

      case err: ClassCastException =>
        Failure(new RuntimeException(s"Class $className must be a " +
          s"subclass of flashbot.core.Strategy", err))
      case err => Failure(err)
    }

  protected[flashbot] def strategyInfo(className: String): Future[StrategyInfo] =
    loadNewStrategy(className).get.info(this)

  protected[flashbot] def allStrategyInfos: Future[Map[String, StrategyInfo]] = {
    val (keys, classNames) = strategyClassNames.toSeq.unzip
    Future.sequence(classNames.map(strategyInfo))
      .map(keys zip _).map(_.toMap)
  }
}
