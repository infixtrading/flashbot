package flashbot.core

import java.io.IOException
import java.util.concurrent.CompletableFuture

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import flashbot.models.{DataPath, Portfolio, StrategyEvent, DataOverride, DataSelection}
import flashbot.util.JavaUtils

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * A thin wrapper around [[Strategy]] that which converts between Java and Scala
  * data structures to provide a native Java API for writing strategies.
  */
abstract class JavaStrategy[P] extends Strategy[P] with TimeSeriesMixin {
  final override def decodeParams(paramsStr: String): Try[P] = {
    try {
      Success(jDecodeParams(paramsStr))
    } catch {
      case err: IOException =>
        Failure(err)
    }
  }

  @throws(classOf[IOException])
  def jDecodeParams(paramsStr: String): P

  final override def info(loader: EngineLoader) =
    JavaUtils.fromJava(jInfo(loader).thenApply[StrategyInfo](info => info))

  def jInfo(loader: EngineLoader): CompletableFuture[StrategyInfo] =
    CompletableFuture.completedFuture(null)

  final override def initialize(portfolio: Portfolio, loader: EngineLoader): Future[Seq[DataPath[Any]]] =
    JavaUtils.fromJava[Seq[DataPath[Any]]](
      jInitialize(portfolio, loader).thenApply[Seq[DataPath[Any]]](paths => paths.asScala))

  def jInitialize(portfolio: Portfolio, loader: EngineLoader)
    : CompletableFuture[java.util.List[DataPath[Any]]]

  final override def onData(data: MarketData[_]): Unit = jHandleData(data)
  def jHandleData(data: MarketData[_])(implicit ctx: TradingSession): Unit

  final override def onEvent(event: OrderEvent): Unit = jHandleEvent(event)
  def jHandleEvent(event: OrderEvent)(implicit ctx: TradingSession): Unit

  final override def resolveMarketData[T](selection: DataSelection[T],
                                          dataServer: ActorRef, dataOverrides: Seq[DataOverride[Any]])
                                         (implicit mat: Materializer, ec: ExecutionContext) =
    JavaUtils.fromJava(jResolveMarketData(selection, dataServer, dataOverrides.asJava))

  def jResolveMarketData[T](selection: DataSelection[T],
                            dataServer: ActorRef, dataOverrides: java.util.List[DataOverride[Any]])
                           (implicit mat: Materializer, ec: ExecutionContext)
      : CompletableFuture[Source[MarketData[T], NotUsed]] = {
    JavaUtils.toJava(super.resolveMarketData(selection, dataServer, dataOverrides.asScala))
  }
}
