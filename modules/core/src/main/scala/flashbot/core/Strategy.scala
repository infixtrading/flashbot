package flashbot.core

import java.util
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import flashbot.server.{ServerMetrics, StreamResponse}
import flashbot.models.{DataOverride, DataSelection, DataStreamReq}
import flashbot.models._
import io.circe._
import com.github.andyglow.jsonschema.AsCirce._
import flashbot.util.MapUtil
import flashbot.util.time.FlashbotTimeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Try

/**
  * Strategy is a container of logic that describes the behavior and data dependencies of a trading
  * strategy. We interact with the outer Flashbot system (placing orders, logging, plotting, etc..)
  * via the TradingSession, which processes all strategy output/side effects as an event stream.
  *
  * Documentation: https://github.com/infixtrading/flashbot/wiki/Writing-Custom-Strategies
  */
abstract class Strategy[P] extends DataHandler {

  /**
    * The trading session that this strategy is running in.
    */
  protected implicit var ctx: TradingSession = _
  protected[flashbot] def setCtx(session: TradingSession): Unit = {
    ctx = session
  }

  /**
    * The JSON decoder used to create an instance of P when the strategy is loaded.
    */
  def decodeParams(paramsStr: String): Try[P]

  /**
    * Human readable title for display purposes.
    */
  def title: String

  /**
    * Generate a self-describing StrategyInfo instance.
    *
    * @param loader an object that can be used to load various types of information about the
    *               context in which the session is being run. E.g. the available exchanges.
    * @return a future of an optional [[StrategyInfo]]. Defaults to `None`.
    */
  def info(loader: EngineLoader): Future[StrategyInfo] = Future.successful(defaultInfo)

  final val defaultInfo: StrategyInfo = StrategyInfo()

  /**
    * During initialization, strategies subscribe to any number of data sets, all of which must be
    * registered in the system or an error is thrown. If all is well, the data sources are loaded
    * and are all queried for a certain time period and results are merged and streamed into the
    * [[onData]] method. Each stream should complete when there is no more data, which auto shuts
    * down the strategy when all data streams complete.
    *
    * @param portfolio the initial portfolio that this trading session is starting with.
    * @param loader an object that can be used to load various types of information about the
    *               context in which the session is being run. E.g. the available exchanges.
    */
  def initialize(portfolio: Portfolio, loader: EngineLoader): Future[Seq[DataPath[Any]]]

  /**
    * Receives the streaming market data that was subscribed to in the [[initialize]] method.
    * The market data streams are merged and sent to this method one item at a time. This method
    * will never be called concurrently. I.e. the next market data item will not be sent to
    * [[onData]] until the previous call returns.
    *
    * @param data a single item of market data from any of the subscribed DataPaths.
    */
  def onData(data: MarketData[_]): Unit

  private var initialPortfolio: Option[Portfolio] = None
  protected val isInitializedMap = new util.WeakHashMap[java.lang.Long, Boolean]()

  def getInitialPortfolio()(implicit ctx: TradingSession): Option[Portfolio] =
    initialPortfolio orElse {
      if (MapUtil.getOrCompute(isInitializedMap, ctx.seqNr, ctx.getPortfolio.isInitialized())) {
        initialPortfolio = Some(ctx.getPortfolio)
      }
      initialPortfolio
    }

  override def aroundOnData(data: MarketData[_])(implicit ctx: TradingSession) = {
    // Call the side effectful function to ensure initialPortfolio is set.
    getInitialPortfolio()

    // Invoke the actual user `handleData` method.
    onData(data)
  }

  /**
    * Receives and handles events that occur in the system. This method is most commonly used
    * to react to fills, e.g. placing a hedge order, or to react to exchange errors.
    *
    * @param event the [[OrderEvent]] describing the event and the context in which it occurred.
    */
  def onEvent(event: OrderEvent): Unit = {}

  /**
    * Idempotent API for placing orders. This method is used to declare the target state of
    * limit orders on the exchange. Flashbot manages the process of creating and cancelling
    * actual limit orders on the exchange so that they conform to the limit order targets
    * declared by this method. Each limit order target is logically identified by the `key`
    * parameter and always corresponds to at-most one actual order on the exchange.
    *
    * @param market the market (exchange and instrument symbol) of the order.
    * @param size the size of the order. May be denominated in any asset whose price can be
    *             implicitly converted to the market's base asset.
    * @param price the price level of the limit order.
    * @param key the logical identifier of this limit order target within the given `market`.
    * @param postOnly whether to allow any portion of this order to execute immediately as
    *                 a taker.
    * @param ctx the trading session instance.
    * @return the target with an id that uniquely identifies this quote.
    */
  protected def limitOrder(market: Market,
                           size: FixedSize,
                           price: Double,
                           key: String,
                           postOnly: Boolean = false)
                          (implicit ctx: TradingSession): OrderTarget = {
    _sendOrder(OrderTarget(
      market,
      key,
      size,
      Some(price),
      once = Some(false),
      postOnly = Some(postOnly)
    ))
  }

  /**
    * Submit a new limit order to the exchange.
    *
    * WARNING! Note that unlike the declarative [[limitOrder]] method, [[limitOrderOnce]] is
    * not idempotent! This makes it considerably harder to write most strategies, as you'll
    * have to do your own bookkeeping. It only exists in case lower level control is required.
    * In general, [[limitOrder]] is the recommended method.
    *
    * @param market the market (exchange and instrument symbol) of the order.
    * @param size the size of the order. May be denominated in any asset whose price can be
    *             implicitly converted to the market's base asset.
    * @param price the price level of the limit order.
    * @param postOnly whether to allow any portion of this order to execute immediately as
    *                 a taker.
    * @param ctx the trading session instance.
    * @return the order target with a randomly generated id.
    */
  protected def limitOrderOnce(market: Market,
                               size: FixedSize,
                               price: BigDecimal,
                               postOnly: Boolean = false)
                              (implicit ctx: TradingSession): OrderTarget = {
    _sendOrder(OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      Some(price),
      once = Some(true),
      postOnly = Some(postOnly)
    ))
  }

  /**
    * Submit a market order to the exchange.
    *
    * @param market the market (exchange and instrument symbol) of the order.
    * @param size the size of the order. May be denominated in any asset whose price can be
    *             implicitly converted to the market's base asset.
    * @param ctx the trading session instance.
    * @return the order target with a randomly generated id.
    */
  protected def marketOrder(market: Market, size: FixedSize)
                           (implicit ctx: TradingSession): OrderTarget = {
    _sendOrder(OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      None
    ))
  }

  private def _transformTarget(target: OrderTarget)
                              (implicit ctx: TradingSession): OrderTarget = (for {
    roundedSize <- ctx.tryRound(target.market, target.size).orElse(Some(target.size))
  } yield target.copy(size = roundedSize)).get

  private def _sendOrder(targetSpec: OrderTarget)
                        (implicit ctx: TradingSession): OrderTarget = {
    val target = _transformTarget(targetSpec)
    ctx.send(target)
    target
  }

  /**
    * The method used by the trading session to provide a market data stream that corresponds
    * to the data path that this strategy subscribed to and some time range.
    *
    * @param selection the path and time range of the requested data stream.
    * @param dataServer DataServer actor that is bound to the session.
    * @param dataOverrides the data overrides provided to the session.
    * @param mat actor materializer for building Akka streams.
    * @param ec execution context for building Akka streams.
    * @tparam T the type of data being requested (Trade, OrderBook, Candle, Ladder, etc...)
    * @return a future of the Akka Source which can be materialized to the requested data stream.
    */
  def resolveMarketData[T](selection: DataSelection[T], dataServer: ActorRef, dataOverrides: Seq[DataOverride[Any]])
                       (implicit mat: Materializer, ec: ExecutionContext)
      : Future[Source[MarketData[T], NotUsed]] = {
    val timeRange = selection.timeRange.get
    val overrideOpt = dataOverrides
      .find(_.path.matches(selection.path))
      .map(_.source)
      .asInstanceOf[Option[Source[MarketData[T], NotUsed]]]
      .map(_.filter(md => md.micros >= timeRange.start && md.micros < timeRange.end))
      .map(Future.successful)

    overrideOpt getOrElse {
      implicit val timeout: Timeout = FlashbotTimeout.default
      (dataServer ? DataStreamReq(selection))
        .mapTo[StreamResponse[MarketData[T]]]
        .map(_.toSource)
    }
  }

  /**
    * Internal state that is used for bookkeeping by the Var type classes. This will be set
    * directly by the TradingSession initialization code.
    */
  implicit def buffer: VarBuffer = _buffer
  private var _buffer: VarBuffer = _
  protected[flashbot] def setVarBuffer(buffer: VarBuffer): Unit = {
    this._buffer = buffer
  }

  /**
    * The bar size used for time series bars.
    */
  def sessionBarSize: FiniteDuration = _sessionBarSize
  private var _sessionBarSize: FiniteDuration = _
  protected[flashbot] def setSessionBarSize(duration: FiniteDuration): Unit = {
    this._sessionBarSize = duration
  }

  /**
    * Internal variable containing the decoded params that were supplied by the user. This variable is
    * set automatically by Flashbot when a trading session is started.
    */
  def params: P = _params
  private var _params: P = _
  protected[flashbot] def setParams(p: P): Unit = {
    this._params = p
  }

  trait SchemaAnnotator[T] {
    def annotate(schema: json.Schema[T]): Json
  }

  implicit def defaultAnn[T]: SchemaAnnotator[T] = new SchemaAnnotator[T] {
    override def annotate(schema: json.Schema[T]) = schema.asCirce()
  }

  implicit class AnnotatorOps[T: SchemaAnnotator](schema: json.Schema[T]) {
    def build: Json = implicitly[SchemaAnnotator[T]].annotate(schema)
  }


  implicit def sessionPrices(implicit ctx: TradingSession): PriceIndex = ctx.getPrices
  implicit def sessionInstruments(implicit ctx: TradingSession): InstrumentIndex = ctx.getInstruments
  implicit def sessionExchangeParams(implicit ctx: TradingSession)
    : Map[String, ExchangeParams] = ctx.getExchangeParams
  implicit def metrics(implicit ctx: TradingSession): Metrics = ServerMetrics
}

trait DataHandler {
  def aroundOnData(data: MarketData[_])(implicit ctx: TradingSession): Unit
}

trait EventHandler {
  def aroundHandleEvent(event: StrategyEvent)(implicit ctx: TradingSession): Unit
}
