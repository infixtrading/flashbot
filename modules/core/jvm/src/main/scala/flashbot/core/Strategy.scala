package flashbot.core

import java.io.IOException
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import flashbot.server.StreamResponse
import flashbot.models.api.{DataOverride, DataSelection, DataStreamReq, OrderTarget}
import flashbot.models.core.FixedSize
import flashbot.models.core._
import io.circe._
import com.github.andyglow.jsonschema.AsCirce._

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
    * [[handleData]] method. Each stream should complete when there is no more data, which auto shuts
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
    * [[handleData]] until the previous call returns.
    *
    * @param data a single item of market data from any of the subscribed DataPaths.
    * @param ctx the trading session instance
    */
  def handleData(data: MarketData[_])(implicit ctx: TradingSession): Unit

  override def aroundHandleData(data: MarketData[_])(implicit ctx: TradingSession) = handleData(data)

  /**
    * Receives and handles events that occur in the system. This method is most commonly used
    * to react to fills, e.g. placing a hedge order, or to react to exchange errors.
    *
    * @param event the [[StrategyEvent]] describing the event and the context in which it occurred.
    * @param ctx the trading session instance
    */
  def handleEvent(event: StrategyEvent)(implicit ctx: TradingSession): Unit = {}

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
    * @return the target id, globally unique within this session.
    */
  protected def limitOrder(market: Market,
                           size: FixedSize[Double],
                           price: Double,
                           key: String,
                           postOnly: Boolean = false)
                          (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      key,
      size,
      Some(price),
      once = Some(false),
      postOnly = Some(postOnly)
    )
    ctx.send(target)
    target.id.toString
  }

  /**
    * Submits a new limit order to the exchange.
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
    * @return the underlying target id, which is randomly generated in this call.
    */
  protected def limitOrderOnce(market: Market,
                               size: FixedSize[Double],
                               price: Double,
                               postOnly: Boolean = false)
                              (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      Some(price),
      once = Some(true),
      postOnly = Some(postOnly)
    )
    ctx.send(target)
    target.id.toString
  }

  /**
    * Submits a market order to the exchange.
    *
    * @param market the market (exchange and instrument symbol) of the order.
    * @param size the size of the order. May be denominated in any asset whose price can be
    *             implicitly converted to the market's base asset.
    * @param ctx the trading session instance.
    * @return the underlying target id, which is randomly generated in this call.
    */
  protected def marketOrder(market: Market, size: FixedSize[Double])
                           (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      None
    )
    ctx.send(target)
    target.id.toString
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
      implicit val timeout: Timeout = Timeout(10 seconds)
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
}

trait DataHandler {
  def aroundHandleData(data: MarketData[_])(implicit ctx: TradingSession): Unit
}
