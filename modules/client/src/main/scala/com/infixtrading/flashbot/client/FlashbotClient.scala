package com.infixtrading.flashbot.client

import java.time.Instant

import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import com.infixtrading.flashbot.core.FlashbotConfig.BotConfig
import com.infixtrading.flashbot.core.MarketData
import com.infixtrading.flashbot.engine.{NetworkSource, StreamResponse}
import com.infixtrading.flashbot.models.api._
import com.infixtrading.flashbot.util._
import com.infixtrading.flashbot.models.core.{Candle, DataPath, TimeRange}
import com.infixtrading.flashbot.report.Report

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag

class FlashbotClient(engine: ActorRef, skipTouch: Boolean = false)(implicit ec: ExecutionContext) {

  implicit val timeout: Timeout = Timeout(10.seconds)

  // This blocks on a ping from the server. This is useful when the client is created immediately
  // after the engine actor is. We will usually want to wait for the engine to initialize before
  // sending any requests to it. Blocking on a ping in the client constructor achieves this.
  if (!skipTouch) {
    this.ping
  }

  def pingAsync = req[Pong](Ping)
  def ping: Pong = await[Pong](pingAsync)

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

  def indexAsync = req[Map[String, DataPath]](MarketDataIndexQuery)
  def index = await(indexAsync)

  /**
    * Returns a non-polling market data stream.
    * If `from` is empty, use the beginning of time.
    * if `to` is empty, sends up to the most recent data available.
    */
  def historicalMarketDataAsync[T](path: DataPath,
                                   from: Option[Instant] = None,
                                   to: Option[Instant] = None) = {
    def singleStream(path: DataPath) = {
      assert(!path.isPattern)
      req[Option[StreamResponse[MarketData[T]]]](DataStreamReq(
        DataSelection(path,
          from.map(_.toEpochMilli * 1000).orElse[Long](Some(0)),
          to.map(_.toEpochMilli * 1000).orElse[Long](Some(Long.MaxValue)))))
        .map(_.map(_.toSource))
    }

    // If the path is not a pattern, request it.
    if (!path.isPattern) singleStream(path)

    // But if the path is a pattern, we have to resolve it to concrete paths from the index
    // and then request them individually and merge.
    else for {
      idx <- indexAsync
      paths = idx.values.toSet.toIterator.filter(_.matches(path))
      allStreamRsps <- Future.sequence(paths.map(path =>
        singleStream(path).flatMap(_.toFut(new RuntimeException(s"Data not found for $path.")))))
    } yield Some(allStreamRsps.reduce(_.mergeSorted(_)(Ordering.by(_.micros))))
  }

  /**
    * Returns a polling stream of live market data.
    * `lookback` specifies the time duration of historical data to prepend to the live data.
    */
  def pollingMarketDataAsync[T](path: DataPath, lookback: Duration = 0.seconds) =
    req[Option[StreamResponse[MarketData[T]]]](DataStreamReq(
      DataSelection(path, Some(Instant.now.minusMillis(lookback.toMillis).toEpochMilli * 1000))))
    .map(_.map(_.toSource))

  def pricesAsync(path: DataPath, timeRange: TimeRange, interval: FiniteDuration) =
    req[Map[String, Vector[Candle]]](PriceQuery(path, timeRange, interval))

  def prices(path: DataPath, timeRange: TimeRange, interval: FiniteDuration) =
    await(pricesAsync(path, timeRange, interval))

  private def req[T](query: Any)(implicit tag: ClassTag[T]): Future[T] = (engine ? query).mapTo[T]
  private def await[T](fut: Future[T]): T = Await.result[T](fut, timeout.duration)

}
