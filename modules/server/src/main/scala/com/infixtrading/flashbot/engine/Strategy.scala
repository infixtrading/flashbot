package com.infixtrading.flashbot.engine

import java.time.Duration
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.pattern.ask
import akka.util.Timeout
import json.Schema
import com.github.andyglow.jsonschema.AsCirce._
import io.circe._
import com.infixtrading.flashbot.core.Instrument.CurrencyPair
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.engine.DataServer.{DataSelection, DataStreamReq}
import com.infixtrading.flashbot.models.api.OrderTarget
import com.infixtrading.flashbot.models.core.FixedSize.FixedSizeD
import com.infixtrading.flashbot.models.core._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * Strategy is a container of logic that describes the behavior and data dependencies of a trading
  * strategy. We interact with the outer Flashbot system (placing orders, logging, plotting, etc..)
  * via the TradingContext, which processes all strategy output/side effects as an event stream.
  * This design is intended to make it easier for us to support remote strategies in the future,
  * possibly written in other languages.
  */
abstract class Strategy {

  type Params
  var params: this.Params = _

  def paramsDecoder: Decoder[this.Params]

  val DEFAULT = "default"

  /**
    * Human readable title for display purposes.
    */
  def title: String

  /**
    * Generate a self-describing StrategyInfo instance given the FlashbotScope in which this
    * strategy will run.
    */
  def info(loader: SessionLoader): Future[Option[StrategyInfo]] = Future.successful(None)

  /**
    * During initialization, strategies declare what data sources they need by name, all of which
    * must be registered in the system or an error is thrown. If all is well, the data sources are
    * loaded and are all queried for a certain time period and results are merged and streamed into
    * the `handleData` method. Each stream should complete when there is no more data, which auto
    * shuts down the strategy when all data streams complete.
    */
  def initialize(portfolio: Portfolio, loader: SessionLoader): Future[Seq[DataPath]]

  /**
    * Receives streaming market data from the sources declared during initialization.
    */
  def handleData(data: MarketData[_])(implicit ctx: TradingSession)

  /**
    * Receives events that occur in the system as a result of actions taken in this strategy.
    */
  def handleEvent(event: StrategyEvent)(implicit ctx: TradingSession): Unit = {}

  /**
    * Receives commands that occur from outside of the system, such as from the UI or API.
    */
  def handleCommand(command: StrategyCommand)(implicit ctx: TradingSession): Unit = {}

  /**
    * RANDOM COMMENT:
    *
    * Example usage of a hypothetical strategy DSL.
    *
    * val up = order("up_limit", size = "10 usd")
    * val downOrder = order(size = "20 usd", market = "btc/usd")
    *
    * val btcPositionOverall = position("my_pos", "btc")
    * val btcPositionBitmex = position("my_pos", "btc", "bitmex")
    *
    * val ethPosition = btcPosition / 2
    *
    * if (something)
    *   btcPosition("usd") = ethPosition("usd")
    *
    * maximize(ethPosition.as("ltc") - btcPosition.as("ltc"))
    *
    */

  @Deprecated
  def orderTargetRatio(exchange: String, product: String, ratio: Double)
                      (implicit ctx: TradingSession): String = {
    val pair = CurrencyPair(product)
    val baseBalance = FixedSize(ctx.getPortfolio.assets(Account(exchange, pair.base)), pair.base)
    val quoteBalance = FixedSize(ctx.getPortfolio.assets(Account(exchange, pair.quote)), pair.quote)

    val notionalBase = baseBalance.as(pair.quote)(ctx.getPrices, ctx.instruments)
    val totalNotional = quoteBalance.qty + notionalBase.qty

    val target = OrderTarget(
      Market(exchange, product),
      DEFAULT,
      FixedSize(totalNotional * ratio, pair.quote),
      None
    )
    ctx.send(target)
    target.id
  }

  def limitOrder(market: Market,
                 size: FixedSizeD,
                 price: Double,
                 key: String = DEFAULT,
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
    target.id
  }

  def limitOrderOnce(market: Market,
                     size: FixedSizeD,
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
    target.id
  }

  def marketOrder(market: Market, size: FixedSizeD)
                 (implicit ctx: TradingSession): String = {
    val target = OrderTarget(
      market,
      UUID.randomUUID().toString,
      size,
      None
    )
    ctx.send(target)
    target.id
  }

  def resolveMarketData(selection: DataSelection, dataServer: ActorRef)
                       (implicit mat: Materializer, ec: ExecutionContext)
      : Future[Source[MarketData[_], NotUsed]] = {
    implicit val timeout: Timeout = Timeout(10 seconds)
    (dataServer ? DataStreamReq(selection))
      .map { case se: StreamResponse[MarketData[_]] => se.toSource }
  }

  /**
    * Internal state that is used for bookkeeping by the Var type classes. This will be set
    * directly by the TradingSession initialization code.
    */
  implicit var buffer: VarBuffer = _

  var sessionBarSize: Duration = _

  protected[engine] def loadParams(jsonParams: Json): Unit = {
    params = paramsDecoder.decodeJson(jsonParams).right.get
  }

  trait SchemaAnnotator[T] {
    def annotate(schema: Schema[T]): Json
  }

  implicit def defaultAnn[T]: SchemaAnnotator[T] = new SchemaAnnotator[T] {
    override def annotate(schema: Schema[T]) = schema.asCirce()
  }

  implicit class AnnotatorOps[T: SchemaAnnotator](schema: Schema[T]) {
    def build: Json = implicitly[SchemaAnnotator[T]].annotate(schema)
  }
}
