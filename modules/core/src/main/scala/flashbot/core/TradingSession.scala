package flashbot.core

import akka.{Done, NotUsed}
import akka.actor.ActorRef
import akka.event.LoggingAdapter
import akka.stream.{KillSwitches, Materializer, OverflowStrategy, SharedKillSwitch}
import akka.stream.scaladsl.{Keep, Source}
import flashbot.core.TradingSession.{DataStream, SessionSetup}
import flashbot.models.api.{DataOverride, TradingSessionEvent}
import flashbot.models.core.{Backtest, Market, Portfolio, TradingSessionMode}
import flashbot.server.PortfolioRef
import io.circe.Json

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future, SyncVar, blocking}
import scala.util.Try


//trait TradingSession {
//  def id: String
////  def send(event: Any): Unit
////  def send(events: mutable.Buffer[Any])
//  def getPortfolio: Portfolio
////  def cmdQueues: java.util.Map[String, CommandQueue]
//  def prices: PriceIndex
//  def instruments: InstrumentIndex
//  protected[flashbot] def exchanges: Map[String, Exchange]
//  def exchangeParams: java.util.Map[String, ExchangeParams]
////  protected[flashbot] def orderManagers: java.util.Map[String, TargetManager]
//
//  // A weak reference to this iteration of the session. It must be GC'd after the current
//  // iteration is done. Therefore, DO NOT HOLD ON TO THIS REFERENCE! Instead, use it as
//  // the key in WeakHashMaps for caching computations in between Strategy mixins.
//  protected[flashbot] def ref: java.lang.Long
//
//  def tryRound(market: Market, size: FixedSize): Option[FixedSize]
//  def round(market: Market, size: FixedSize): FixedSize =
//    tryRound(market, size).getOrElse({
//      throw new RuntimeException(s"Can't round $size for market $market")
//    })
//}

trait TickCollector {
  def insertTick(event: ): Unit
}

class BacktestTickCollector extends TickCollector {
  override def insert(event: Any) = ???
}

/**
  * Live tick collector simply sends each tick to the tick actor ref.
  */
class LiveTickCollector(tickRef: ActorRef) extends TickCollector {
  override def insert(tick: Tick) = tickRef ! tick
}



class TradingSession(val id: String,
                     val strategyKey: String,
                     val params: Json,
                     val mode: TradingSessionMode,
                     val dataServer: ActorRef,
                     val loader: EngineLoader,
                     val log: LoggingAdapter,
                     val report: Report,
                     private val sessionEventsRef: ActorRef,
                     private val portfolioRef: PortfolioRef,
                     private val dataOverrides: Seq[DataOverride[_]],
                     private implicit val mat: Materializer,
                     private implicit val ec: ExecutionContext) {

  val prices: PriceIndex = new JPriceIndex(GraphConversions)
  lazy val instruments: InstrumentIndex = load.value.get.get.instruments
  lazy val exchanges: Map[String, Exchange] = load.value.get.get.exchanges
  private lazy val dataStreams = load.value.get.get.streams

  var scope: OrderRef = _

  // Initialize an order and invoke it's submit method.
  def submit(order: OrderRef): OrderRef = {
    order.ctx = this
    order.submit()
    order
  }

  // Lookup an order by id and cancel it.
  def cancel(id: String): Unit = {
  }


  /**
    * This method is the only way for events to be triggered in the session.
    */
  def emitEvent(event: TradingSessionEvent) = {
  }

  def genOrderId(order: OrderRef): String = {
  }

  private var killSwitch: SyncVar[Option[SharedKillSwitch]] = new SyncVar()
  killSwitch.put(None)

  // If a tick ref exists, it's corresponding tick source is merged directly into the
  // market data stream with no sorting.
  private val tickRefOpt =
    if (mode.isBacktest) None
    else Some(Source.actorRef[Tick](Int.MaxValue, OverflowStrategy.fail).preMaterialize())

  // If a tick heap exists, it is polled for events to synchronously process before
  // each item of market data comes in.
  private val tickHeapOpt =
    if (mode.isBacktest) Some(EventLoop.empty)
    else None

  protected[server] val exchangeParams: java.util.HashMap[String, ExchangeParams] =
    buildExMap(_.params)
  protected[server] val clientIdToOrderId: IDMap =
    buildExMap(_ => new java.util.HashMap[String, String]())
  protected[server] val orderIdToClientId: IDMap =
    buildExMap(_ => new java.util.HashMap[String, String]())
  protected[flashbot] var ref: java.lang.Long = -1L

  /**
    * The "main method". It will never run more than once per instance.
    */
  protected[flashbot] lazy val run: Future[Report] = {
    assert(killSwitch.take().isEmpty)
    val ks = KillSwitches.shared(id)
    for {
      // Load all setup vals
      _ <- load

      // Prepare market data streams
      (dataStreamsDone, marketDataStream) =

        // If this trading session is a backtest then we merge the data streams by time.
        // But if this is a live trading session then data is sent first come, first serve
        // to keep latencies low.
        dataStreams.reduce[DataStream](
            if (mode.isBacktest) _.mergeSorted(_)
            else _.merge(_))
          .watchTermination()(Keep.right)
          .preMaterialize()

      // Shutdown the session when market data streams are complete.
      _ = dataStreamsDone.onComplete(_ => ks.shutdown())

      // Merge the tick stream into the main data stream for live data. This will only occur
      // for live and paper trading sessions. Backtests don't have a tick ref.
      inputStream =
        if (tickRefOpt.isDefined)
          marketDataStream.mergeSorted(tickRefOpt.get._2)
        else marketDataStream

      // Set the kill switch
      _ = { killSwitch.put(Some(ks)) }

      // Lift-off
      _ <- inputStream.runForeach { input =>

      }

    } yield report
  }


  def buildExMap[T](fn: Exchange => T): java.util.HashMap[String, T] = {
    val map = new java.util.HashMap[String, T]()
    exchanges.foreach { ex =>
      map(ex._1) = fn(ex._2)
    }
    map
  }

  def shutdown(): Future[Done] = for {
    ks <- Future(blocking(killSwitch.take))
    _ <-
      if (ks.isEmpty) Future.failed(
        new RuntimeException("TradingSession has not been started."))
      else {
        ks.get.shutdown()
        report
      }
    _ = { killSwitch.put(ks) }
  } yield Done

  /**
    * Events sent to the session are either emitted as a Tick, or added to the event buffer
    * if we can, so that the strategy can process events synchronously when possible.
    */
//        protected[server] var sendFn: m.Buffer[Any] => Unit = { _ =>
//          throw new RuntimeException("sendFn not defined yet.")
//        }

  // Reusable buffer for sending single events.
//        private val singleEventBuf: m.Buffer[Any] = m.ArrayBuffer.empty

//        def send(event: Any): Unit = {
//          singleEventBuf(0) = event
//          sendFn(singleEventBuf)
//        }
//
//        def send(events: m.Buffer[Any]): Unit = {
//          sendFn(events)
//        }

  override def getPortfolio = portfolioRef.getPortfolio(Some(instruments))

  override def tryRound(market: Market, size: FixedSize) = {
    val instrument = instruments(market)
    val exchange = exchanges(market.exchange)
    if (size.security == instrument.security.get)
      Some(size.map(exchange.roundBase(instrument)))
    else if (size.security == instrument.settledIn.get)
      Some(size.map(exchange.roundQuote(instrument)))
    else None
  }

  def newOrderId(clientId: String, orderId: String): Unit = {

  }

  def rmOrderId(orderId: String): Unit = {

  }


  def submitOrder(): Unit = {

  }

  def cancelOrder(): Unit = {

  }

  def manageOrder(name: String, clazz: Class[_ <: ManagedOrder[P]], props: P): Unit = {

  }

  protected[flashbot] lazy val load: Future[SessionSetup] = {

    val exchangeConfigs = loader.getExchangeConfigs()

    log.debug("Exchange configs: {}", exchangeConfigs)

    // Set the time. Using system time just this once.
    val sessionMicros = currentTimeMicros

    def dataSelection[T](path: DataPath[T]): DataSelection[T] = mode match {
      case Backtest(range) =>
        DataSelection(path, Some(range.start), Some(range.end))
      case liveOrPaper =>
        DataSelection(path, Some(sessionMicros - liveOrPaper.lookback.toMicros), None)
    }

    // Load a new instance of an exchange.
    def loadExchange(name: String): Try[Exchange] =
      loader.loadNewExchange(name)
        .map(plainInstance => {

          // Wrap it in our Simulator if necessary.
          val instance = if (mode == Live) plainInstance else new Simulator(plainInstance)

          // Set the tick function. This is a hack that maybe we should remove later.
          instance.setTickFn((nowMicros: Long) => {
            // emitTick will update the tick queue iff the session is a backtest.
            // Backtests use the simulator, and will never emit ticks asynchronously.
            // Therefore, there is no race condition regarding updating the tick queue.
            emitTick(Tick(Seq.empty, Some(name), nowMicros))
          })

          instance
        })

    log.debug("Starting async setup")

    for {
      // Check that we have a config for the requested strategy.
      strategyClassName <- strategyClassNames.get(strategyKey)
        .toTry(s"Unknown strategy: $strategyKey").toFut

      _ = { log.debug("Found strategy class") }

      // Load the strategy
      rawStrategy <- Future.fromTry[Strategy[P]](loader.loadNewStrategy[P](strategyClassName))

      strategy <- for {
        // Decode the params
        params <- rawStrategy.decodeParams(strategyParams.noSpaces).toFut

        // Load params into strategy
        _ = rawStrategy.setParams(params)

        // Set the var buffer
        _ = rawStrategy.setVarBuffer(new VarBuffer(initialReport.values.mapValues(_.value)))

        // Set the bar size
        _ = rawStrategy.setSessionBarSize(initialReport.barSize)
      } yield rawStrategy

      // Load the instruments.
      instruments <- loader.loadInstruments

      // Initialize the strategy and collect data paths
      paths <- strategy.initialize(portfolioRef.getPortfolio(Some(instruments)), loader)

      // Load the exchanges
      exchangeNames: Set[String] = paths.toSet[DataPath[_]].map(_.source)
        .intersect(exchangeConfigs.keySet)
      _ = { log.debug("Loading exchanges: {}.", exchangeNames) }

      exchanges: Map[String, Exchange] <- Future.sequence(exchangeNames.map(n =>
        loadExchange(n).map(n -> _).toFut)).map(_.toMap)
      _ = { log.debug("Loaded exchanges: {}.", exchanges) }

      // Resolve market data streams.
      streams <- Future.sequence(paths.map(path =>
        strategy.resolveMarketData(dataSelection(path), dataServer, dataOverrides)))

      _ = { log.debug("Resolved {} market data streams out of" +
        " {} paths requested by the strategy.", streams.size, paths.size) }

    } yield
      SessionSetup(instruments, exchanges, strategy, streams, sessionMicros)
  }

  def mergeMarketData(a: DataStream, b: DataStream): DataStream =

}

object TradingSession {
  type DataStream = Source[MarketData[_], NotUsed]
  type InputStream = Source[SessionInput, NotUsed]

  case class SessionSetup(instruments: InstrumentIndex,
                          exchanges: Map[String, Exchange],
                          strategy: Strategy[_],
                          sessionId: String,
                          streams: Seq[Source[MarketData[_], NotUsed]],
                          sessionMicros: Long)

  def closeActionForOrderId(actions: CommandQueue, ids: IdManager, id: String): CommandQueue =
    actions match {
      case ActionQueue(Some(action), _) if ids.actualIdForTargetId(action.targetId).contains(id) =>
        actions.closeActive
      case _ => actions
    }

  implicit def implicitInstruments(implicit ctx: TradingSession): InstrumentIndex = ctx.getInstruments
  implicit def implicitPrices(implicit ctx: TradingSession): PriceIndex = ctx.getPrices


}
