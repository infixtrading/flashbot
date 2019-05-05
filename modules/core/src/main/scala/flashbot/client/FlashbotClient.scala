package flashbot.client

import java.time.Instant

import akka.actor.{Actor, ActorPath, ActorRef, ActorSystem, Props}
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{Done, NotUsed}
import flashbot.client.FlashbotClient._
import flashbot.core.DataType.{LadderType, OrderBookType}
import flashbot.core.FlashbotConfig.BotConfig
import flashbot.core._
import flashbot.models._
import flashbot.server.{NetworkSource, StreamResponse}
import flashbot.util.time.FlashbotTimeout
import io.circe.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag

/**
  * FlashbotClient is the primary way of interacting with a Flashbot system. It requires an
  * ActorRef of a JVM-local TradingEngine as the entry point. Every method has both a blocking
  * version (e.g. client.ping()) and a non-blocking async [[Future]] based version (e.g.
  * client.pingAsync()).
  *
  * @param engine the TradingEngine which serves as the entry point to the FlashbotSystem
  *               this client is connecting to.
  * @param skipTouch whether to immediately return from the FlashbotClient constructor, even
  *                  if the [[engine]] has not yet responded to ping. The default is to block
  *                  until the engine responds so that it's guaranteed to be initialized by
  *                  the time the constructor returns.
  */
class FlashbotClient(engine: ActorRef, skipTouch: Boolean = false) {

  /**
    * Alternative constructor for when the ActorRef is not available. This blocks on a ping
    * to the given actor path to retreive the ActorRef of the engine. `skipTouch` is set to
    * `true` because the touch already happened.
    */
  def this(path: ActorPath)(implicit system: ActorSystem) = {
    this(Await.result[ActorRef](tradingEngineRef(path), timeout.duration), skipTouch = true)
  }

  // This blocks on a ping from the server. This is useful when the client is created immediately
  // after the engine actor is. We will usually want to wait for the engine to initialize before
  // sending any requests to it. Blocking on a ping in the client constructor achieves this.
  if (!skipTouch) {
    this.ping()
  }

  def pingAsync(): Future[Pong] = req[Pong](Ping)
  def ping(): Pong = await[Pong](pingAsync())

  def configureBotAsync(id: String, config: BotConfig): Future[Done] = req[Done](ConfigureBot(id, config))
  def configureBot(id: String, config: BotConfig): Unit = await(configureBotAsync(id, config))

  def botStatusAsync(id: String): Future[BotStatus] = req[BotStatus](BotStatusQuery(id))
  def botStatus(id: String): BotStatus = await(botStatusAsync(id))

  def enableBotAsync(id: String): Future[Done] = req[Done](EnableBot(id))
  def enableBot(id: String): Unit = await(enableBotAsync(id))

  def disableBotAsync(id: String): Future[Done] = req[Done](DisableBot(id))
  def disableBot(id: String): Unit = await(disableBotAsync(id))

  def botHeartbeatAsync(id: String): Future[Done] = req[Done](BotHeartbeat(id))
  def botHeartbeat(id: String): Unit = await(botHeartbeatAsync(id))

  def subscribeToReportAsync(id: String): Future[Source[Report, NotUsed]] =
    req[NetworkSource[Report]](SubscribeToReport(id)).map(_.toSource)
  def subscribeToReport(id: String): Source[Report, NotUsed] = await(subscribeToReportAsync(id))

  def indexAsync(): Future[Map[String, DataPath[Any]]] = req[Map[String, DataPath[Any]]](MarketDataIndexQuery)
  def index(): Map[String, DataPath[Any]] = await(indexAsync())

  def pricesAsync(path: DataPath[Priced], timeRange: TimeRange,
                  interval: FiniteDuration) : Future[Map[String, Vector[Candle]]] =
    req[Map[String, Vector[Candle]]](PriceQuery(path, timeRange, interval))

  def prices(path: DataPath[Priced], timeRange: TimeRange,
             interval: FiniteDuration): Map[String, Vector[Candle]] =
    await(pricesAsync(path, timeRange, interval))

  def backtestAsync(strategy: String, params: Json, initialPortfolio: String,
                    interval: FiniteDuration = 1 day, timeRange: TimeRange = TimeRange(0),
                    dataOverrides: Seq[DataOverride[_]] = Seq.empty): Future[Report] =
    req[ReportResponse](BacktestQuery(strategy, params, timeRange, initialPortfolio,
      Some(interval), None, dataOverrides)).map(_.report)

  def backtest(strategy: String, params: Json, initialPortfolio: String,
               interval: FiniteDuration = 1 day, timeRange: TimeRange = TimeRange(0),
               dataOverrides: Seq[DataOverride[_]] = Seq.empty): Report =
    await(backtestAsync(strategy, params, initialPortfolio, interval, timeRange, dataOverrides))

  /**
    * Returns a polling stream of live market data.
    * `lookback` specifies the time duration of historical data to prepend to the live data.
    */
  def pollingMarketDataAsync[T](path: DataPath[T], lookback: Duration = 0 seconds)
      : Future[Source[MarketData[T], NotUsed]] =
    req[StreamResponse[MarketData[T]]](DataStreamReq(
      DataSelection(path, Some(Instant.now.minusMillis(lookback.toMillis).toEpochMilli * 1000))))
    .map(_.toSource)
    .recoverLadder(path,
      pollingMarketDataAsync[OrderBook](path.withType(OrderBookType), lookback))

  def pollingMarketData[T](path: DataPath[T], lookback: Duration = 0 seconds)
      : Source[MarketData[T], NotUsed] =
    await(pollingMarketDataAsync(path, lookback))

  /**
    * Returns a non-polling market data stream.
    * If `from` is empty, use the beginning of time.
    * if `to` is empty, sends up to the most recent data available.
    */
  def historicalMarketDataAsync[T](path: DataPath[T],
                                   from: Option[Instant] = None,
                                   to: Option[Instant] = None,
                                   limit: Option[TakeLimit] = None)
      : Future[Source[MarketData[T], NotUsed]] = {

    def singleStream[D](p: DataPath[D]): Future[Source[MarketData[D], NotUsed]] = {
      assert(!p.isPattern)
      req[StreamResponse[MarketData[D]]](DataStreamReq(
        DataSelection(p,
          from.map(_.toEpochMilli * 1000).orElse[Long](Some(0)),
          to.map(_.toEpochMilli * 1000).orElse[Long](Some(Long.MaxValue))),
        limit.getOrElse(TakeLimit.default)))
          .map(_.toSource)
          .recoverLadder(p, singleStream[OrderBook](p.withType(OrderBookType)))
    }

    // If the path is not a pattern, request it.
    if (!path.isPattern) singleStream(path)

    // But if the path is a pattern, we have to resolve it to concrete paths from the index
    // and then request them individually and merge.
    else for {
      idx: Map[String, DataPath[Any]] <- indexAsync()
      paths = idx.values.toSet.toIterator.map((x: DataPath[Any]) =>
        x.filter(path)).collect { case Some(x) => x }
      allStreamRsps <- Future.sequence(paths.map(singleStream(_: DataPath[T])))
    } yield allStreamRsps.reduce(_.mergeSorted(_)(Ordering.by(_.micros)))
  }

  def historicalMarketData[T](path: DataPath[T],
                              from: Option[Instant] = None,
                              to: Option[Instant] = None,
                              limit: Option[TakeLimit] = None)
      : Source[MarketData[T], NotUsed] =
    await(historicalMarketDataAsync[T](path, from, to, limit))

  private def req[T](query: Any)(implicit tag: ClassTag[T]): Future[T] =
    (engine ? query).mapTo[T]

  private def await[T](fut: Future[T]): T = Await.result[T](fut, timeout.duration)

  implicit class RecoverOps[T](future: Future[Source[MarketData[T], NotUsed]]) {

    def recoverNotFound(fut: =>Future[Source[MarketData[T], NotUsed]]): Future[Source[MarketData[T], NotUsed]] =
      future.recoverWith { case err: DataNotFound[T] => fut }

    // TODO: Implement this fully
    def recoverLadder(path: DataPath[T],
                      fut: => Future[Source[MarketData[OrderBook], NotUsed]])
        : Future[Source[MarketData[T], NotUsed]] =

      path.datatype match {
        case ladderType: LadderType =>
          future.recoverNotFound(fut.map(_.statefulMapConcat(() => {
            var ladder: Option[Ladder] = None
            md: MarketData[OrderBook] => {
              var lastUpdate = md.data.getLastUpdate
              if (ladder.isEmpty || lastUpdate.isEmpty) {
                ladder = Some(Ladder.fromOrderBook(10, md.data))

              } else if (lastUpdate.nonEmpty) {
                md.data.getLastUpdate match {
                  case Some(ev: OrderBook.Open) =>
                    ladder.get.qtyAtPrice(ev.price)
//                    ladder.get.updateLevel(md.data.quoteSideOfPrice(ev.price), ev.price, ev.)
                  case Some(ev: OrderBook.Done) =>
                  case Some(ev: OrderBook.Change) =>
                }
//                ladder.get.updateLevel()
              }
              List(md.withData[T](ladder.asInstanceOf[T], ladderType))
            }
          })))
        case _ => future
      }
  }
}

object FlashbotClient {

  implicit val timeout: Timeout = FlashbotTimeout.default

  /**
    * A lightweight actor that collects ActorRefs of TradingEngine actors in the system.
    */
  class EngineCollector extends Actor {
    val refPromise = Promise[ActorRef]
    override def receive = {
      case Pong(_) =>
        if (!refPromise.isCompleted)
          refPromise.success(sender)
      case GetSender(path) =>
        context.actorSelection(path) ! Ping
        (refPromise.future pipeTo sender) andThen {
          case _ => context.stop(self)
        }
    }
  }

  case class GetSender(path: ActorPath)

  def tradingEngineRef(path: ActorPath)(implicit system: ActorSystem): Future[ActorRef] = {
    val collector = system.actorOf(Props[EngineCollector])
    (collector ? GetSender(path)).mapTo[ActorRef]
  }
}
