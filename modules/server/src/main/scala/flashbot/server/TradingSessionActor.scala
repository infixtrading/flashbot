package flashbot.server

import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream._
import breeze.stats.distributions.Gaussian
import io.circe._
import io.circe.syntax._
import flashbot.util.stream._
import flashbot.util._
import flashbot.util.time.currentTimeMicros
import flashbot.core.{Strategy, _}
import flashbot.models.api.{DataOverride, DataSelection, LogMessage, OrderTarget}
import flashbot.models.core.Action._
import flashbot.models.core._
import flashbot.core.Report.ReportError
import flashbot.core.ReportEvent._
import flashbot.core.TradingSession._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future, SyncVar}
import scala.util.{Failure, Success, Try}
import de.sciss.fingertree._
import flashbot.models.core.Order.Buy

class TradingSessionActor(loader: EngineLoader,
                          strategyClassNames: Map[String, String],
                          strategyKey: String,
                          strategyParams: Json,
                          mode: TradingSessionMode,
                          sessionEventsRef: ActorRef,
                          portfolioRef: PortfolioRef,
                          initialReport: Report,
                          dataServer: ActorRef,
                          dataOverrides: Seq[DataOverride[_]]) extends Actor with ActorLogging {

  import TradingSessionActor._

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = buildMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  implicit val tickOrderBy = (tick: Tick) => tick.micros

  // Setup a thread safe reference to an event buffer which allows the session to process
  // events synchronously when possible.
  val eventBuffer: SyncVar[mutable.Buffer[Any]] = new SyncVar[mutable.Buffer[Any]]

  // In backtest mode we use a tick queue to simulate ticks in the stream's scan function
  // instead of using the `tickRef`. This is because the `tickRef` is only useful for ticks
  // to trigger the scan function for real time purposes. Otherwise, in backtests, we'd like to
  // ensure proper ordering of ticks in relation to incoming market data.
  var tickQueue = OrderedSeq.empty[Tick, Long]

  // Helper function for processing queued up ticks when scanning over market data.
  def dequeueTicksFor(item: Either[MarketData[_], Tick]): Seq[Either[MarketData[_], Tick]] = {
    val tickSeq = tickQueue.iterator.toList.toSeq
    val dequeued = if (mode.isBacktest) {
      val data = item.left.toOption
      tickSeq
        .takeWhile(tick => data.isEmpty || data.get.micros >= tick.micros)
        .map(Right(_))
    } else Seq.empty
    tickQueue = OrderedSeq(tickSeq.drop(dequeued.size):_*)
    dequeued
  }

  // Allows the session to respond to events other than incoming market data. Exchange instances
  // can send ticks to let the session know that they have events ready to be collected. Strategies
  // can also send events to the session. These are stored in tick.event.
  var tickRefOpt: Option[ActorRef] = None
  def emitTick(tick: Tick): Unit = {
    if (mode.isBacktest) {
      assert(tick.micros >= 0, "Backtests do not support asynchronous events.")
      tickQueue += tick
    } else tickRefOpt match {
      case Some(ref) => ref ! tick
      case None => log.warning(s"Ignoring tick $tick because the tick ref actor is not loaded.")
    }
  }

  var killSwitch: Option[SharedKillSwitch] = None

  def setup[P](): Future[SessionSetup] = {

    val exchangeConfigs = loader.getExchangeConfigs()

    log.debug("Exchange configs: {}", exchangeConfigs)

    // Set the time. Using system time just this once.
    val sessionMicros = currentTimeMicros
    val now = Instant.ofEpochMilli(sessionMicros / 1000)

    def dataSelection[T](path: DataPath[T]): DataSelection[T] = mode match {
      case Backtest(range) => DataSelection(path, Some(range.start), Some(range.end))
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

      initialPortfolio = portfolioRef.getPortfolio(Some(instruments))

      // Initialize the strategy and collect data paths
      paths <- strategy.initialize(initialPortfolio, loader)

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
      SessionSetup(instruments, exchanges, strategy,
        UUID.randomUUID.toString, streams, sessionMicros, initialPortfolio)
  }

  val gaussian = Gaussian(0, 1)

  def runSession(sessionSetup: SessionSetup): String = sessionSetup match {
    case SessionSetup(instruments, exchanges, strategy, sessionId, streams,
          sessionMicros, initialPortfolio) =>
      implicit val conversions = GraphConversions

      Metrics.observe("streams_per_trading_session", streams.size)

      killSwitch = Some(KillSwitches.shared(sessionId))

      /**
        * The trading session that we fold market data over.
        */
      class Session(protected[server] val instruments: InstrumentIndex = instruments,
                    protected[server] var portfolio: Portfolio = initialPortfolio,
                    protected[server] var prices: PriceIndex = PriceIndex.empty,
                    protected[server] var orderManagers: Map[String, TargetManager] =
                        exchanges.mapValues(_ => TargetManager(instruments)),
                    // Create action queue for every exchange
                    protected[server] var actionQueues: Map[String, ActionQueue] =
                        exchanges.mapValues(_ => ActionQueue()),
                    protected[server] var emittedReportEvents: Seq[ReportEvent] = Seq.empty,
                    protected[server] val exchangeParams: Map[String, ExchangeParams] =
                        exchanges.mapValues(_.params))
        extends TradingSession {

        override val id: String = sessionId

        /**
          * Events sent to the session are either emitted as a Tick, or added to the event buffer
          * if we can, so that the strategy can process events synchronously when possible.
          */
        protected[server] var sendFn: Seq[Any] => Unit = { _ =>
          throw new RuntimeException("sendFn not defined yet.")
        }

        def send(events: Any*): Unit = {
          sendFn(events)
        }

        override def getPortfolio = portfolio
        override def getActionQueues = actionQueues
        override def getPrices = prices
        override def getInstruments = instruments
        override def getExchangeParams = exchangeParams
      }

      def processDataOrTick(session: Session,
                            dataOrTick: Either[MarketData[_], Tick]): Session = {

        // Load the portfolio into the session from the PortfolioRef on every scan iteration.
        val initPortfolio = portfolioRef.getPortfolio(Some(instruments))
        session.portfolio = initPortfolio

        // First, setup the event buffer so that we can handle synchronous events.
        val thisEventBuffer: mutable.Buffer[Any] = new ArrayBuffer[Any]()
        eventBuffer.put(thisEventBuffer)

        // Set the session `sendFn` function. Close over `thisEventBuffer` and check that it
        // matches the one in the syncvar. Only then can we append to it, otherwise, emit it
        // as an async tick.
        session.sendFn = (events: Seq[Any]) => {
          if (eventBuffer.isSet) {
            val buf = eventBuffer.get(5L)
            // Check the syncvar for reference equality with our buffer.
            if (buf.isDefined && (buf.get eq thisEventBuffer)) {
              thisEventBuffer ++= events
            } else {
              emitTick(Tick(events, None, -1))
            }
          } else {
            emitTick(Tick(events, None, -1))
          }
        }

        implicit val ctx: TradingSession = session
        implicit val idx: InstrumentIndex = ctx.getInstruments

        // Split up `dataOrTick` into two Options
        val (tick, data) = dataOrTick match {
          case Right(t: Tick) => (Some(t), None)
          case Left(md: MarketData[_]) => (None, Some(md))
        }

        // An optional string that represents the exchange tied to this scan iteration.
        val ex: Option[String] = data
          .map(_.source)
          .orElse(tick.flatMap(_.exchange))
          .filter(exchanges.isDefinedAt)
        val exchange: Option[Exchange] = ex.map(exchanges(_))

        // If this data has price info attached, save it to the price index. Also update
        // the portfolio in case there are any positions that need to be initialized.
        data.map(_.data) match {
          case Some(pd: Priced) =>
            session.prices.setPrice(Market(data.get.source, data.get.topic), pd.price)
            session.portfolio = session.portfolio.initializePositions(session.prices, session.getInstruments)
          case _ =>
        }

        // Update the relevant exchange with the market data to collect fills and user data
        val (fills, userData, errors) = exchange
          .map(_.collect(session, data, tick))
          .getOrElse((Seq.empty, Seq.empty, Seq.empty))

        // TODO: Add support for logging errors in the Report.
        for (err <- errors) {
          err match {
            case OrderRejected(req, reason) =>
              session.orderManagers = session.orderManagers.updated(ex.get,
                session.orderManagers(ex.get).orderRejected(req.clientOid))
              session.actionQueues = session.actionQueues.updated(ex.get,
                session.actionQueues(ex.get).closeActive)
          }
          strategy.handleEvent(ExchangeErrorEvent(err))
        }

        userData.foldLeft((session.orderManagers, session.actionQueues)) {
          /**
            * Either market or limit order received by the exchange. Associate the client id
            * with the exchange id. Do not close any actions yet. Wait until the order is
            * open, in the case of limit order, or done if it's a market order.
            */
          case (memo @ (os, as), o @ OrderReceived(id, _, clientId, _)) =>
            val targetOpt = os(ex.get).ids.clientToTarget.get(clientId.get)
            strategy.handleEvent(OrderTargetEvent(targetOpt, o))
            if (targetOpt.isDefined)
              (os.updated(ex.get, os(ex.get).receivedOrder(clientId.get, id)), as)
            else memo

          /**
            * Limit order is opened on the exchange. Close the action that submitted it.
            */
          case (memo @ (os, as), o @ OrderOpen(id, product, price, size, side)) =>
            val targetOpt = os(ex.get).ids.actualToTarget.get(id)
            val market = Market(ex.get, product)
            strategy.handleEvent(OrderTargetEvent(targetOpt, o))
            session.portfolio = session.portfolio
              .withOrder(Some(id), market, if (side == Buy) size else -size, price)
            if (targetOpt.isDefined)
              (os.updated(ex.get, os(ex.get).openOrder(o)),
                as.updated(ex.get, closeActionForOrderId(as(ex.get), os(ex.get).ids, id)))
            else memo

          /**
            * Either market or limit order is done. Could be due to a fill, or a cancel.
            * Disassociate the ids to keep memory bounded in the ids manager. Also close
            * the action for the order id.
            */
          case (memo @ (os, as), o @ OrderDone(id, product, _, _, _, _)) =>
            val targetOpt = os(ex.get).ids.actualToTarget.get(id)
            val market = Market(ex.get, product)
            strategy.handleEvent(OrderTargetEvent(targetOpt, o))
            session.portfolio = session.portfolio.withoutOrder(market, id)
            if (targetOpt.isDefined)
              (os.updated(ex.get, os(ex.get).orderComplete(id)),
                as.updated(ex.get, closeActionForOrderId(as(ex.get), os(ex.get).ids, id)))
            else memo

        } match {
          case (newOMs, newActions) =>
            session.orderManagers = newOMs
            session.actionQueues = newActions
        }

        def acc(currency: String) = Account(ex.get, currency)
        session.portfolio = fills.foldLeft(session.portfolio) {
          case (portfolio, fill) =>

            session.send(CollectionEvent("fill_size", fill.size.asJson))
            session.send(CollectionEvent("gauss", (gaussian.draw() * 10 + 5).asJson))

            val instrument = instruments(ex.get, fill.instrument)
            val market = Market(ex.get, instrument.symbol)

            // Execute the fill on the portfolio
            val newPortfolio = portfolio.fillOrder(market, fill)(instruments, session.exchangeParams)

            // Emit a trade event when we see a fill
            session.send(TradeEvent(
              fill.tradeId, ex.get, fill.instrument.toString,
              fill.micros, fill.price, fill.size))

            // Emit portfolio info:
            // The settlement account must be an asset
            val settlementAccount = acc(instrument.settledIn.get)
            session.send(BalanceEvent(settlementAccount,
              newPortfolio.balance(settlementAccount).qty, fill.micros))

            // The security account may be a position or an asset
            val position = portfolio.positions.get(market)
            if (position.isDefined) {
              session.send(PositionEvent(market, position.get, fill.micros))
            } else {
              val assetAccount = acc(instrument.security.get)
              session.send(BalanceEvent(assetAccount,
                portfolio.balance(assetAccount).qty, fill.micros))
            }

            // Return updated portfolio
            newPortfolio
        }

        // Call aroundHandleData and catch user errors.
        data match {
          case Some(md) =>
            val timer = Metrics.startTimer("handle_data_ms")
            try {
              strategy.aroundHandleData(md)(session)
            } catch {
              case e: Throwable =>
                Metrics.inc("handle_data_error")
                log.error(e, "Handle data error")
            } finally {
              timer.observeDuration()
            }
          case None =>
        }

        // Take our event buffer from the syncvar. This allows the next scan iteration to `put`
        // theirs in. Otherwise, `put` will block. Append to the events we received from a tick.
        val events = tick.map(_.events).getOrElse(Seq.empty) ++ eventBuffer.take()

        // Process the events.
        events foreach {
          // Send report events to the events actor ref.
          case reportEvent: ReportEvent =>
            val re = reportEvent match {
              case ce: CandleEvent => ce match {
                case ev: CandleAdd => ev.copy(ev.series)
                case ev: CandleUpdate => ev.copy(ev.series)
              }
              case otherReportEvent => otherReportEvent
            }
            sessionEventsRef ! re

          // Send order targets to their corresponding order manager.
          case target: OrderTarget =>
            session.orderManagers += (target.market.exchange ->
              session.orderManagers(target.market.exchange).submitTarget(target))

          case LogMessage(msg) =>
            log.info(msg)
        }

        // If necessary, expand the next target into actions and enqueue them for each
        // order manager.
        session.orderManagers.foreach {
          case (exName, om) =>
            om.enqueueActions(exchanges(exName), session.actionQueues(exName))(
              session.prices, session.getInstruments) match {
                case (newOm, newActions) =>
                  session.orderManagers += (exName -> newOm)
                  session.actionQueues += (exName -> newActions)
              }
        }

        // Here is where we tell the exchanges to do stuff, like place or cancel orders.
        session.actionQueues.foreach { case (exName, acs) =>
          acs match {
            case ActionQueue(None, next +: rest) =>
              session.actionQueues += (exName -> ActionQueue(Some(next), rest))
              next match {
                case action @ PostMarketOrder(clientId, targetId, side, size, funds) =>
                  session.orderManagers += (exName ->
                    session.orderManagers(exName).initCreateOrder(targetId, clientId, action))
                  exchanges(exName)._order(
                    MarketOrderRequest(clientId, side, targetId.instrument, size, funds))

                case action @ PostLimitOrder(clientId, targetId, side, size, price, postOnly) =>
                  session.orderManagers += (exName ->
                    session.orderManagers(exName).initCreateOrder(targetId, clientId, action))
                  exchanges(exName)
                    ._order(LimitOrderRequest(clientId, side, targetId.instrument, size, price, postOnly))

                case action @ CancelLimitOrder(targetId) =>
                  session.orderManagers += (exName ->
                    session.orderManagers(exName).initCancelOrder(targetId))
                  // May not exist if order was rejected.
                  val actualOpt = session.orderManagers(exName).ids.actualIdForTargetId(targetId)
                  if (actualOpt.isDefined)
                    exchanges(exName)._cancel(actualOpt.get , targetId.instrument)
              }
            case _ =>
          }
        }

        // At the end of every scan iteration, we merge the resulting portfolio changes into
        // the portfolio ref.
        val portfolioDiff = session.portfolio.diff(initPortfolio)
        portfolioRef.mergePortfolio(portfolioDiff)

        session
      }


      // Merge market data streams and send the the data into the strategy instance. If this
      // trading session is a backtest then we merge the data streams by time. But if this is a
      // live trading session then data is sent first come, first serve to keep latencies low.
      val (tickRef, fut) = streams.reduce[Source[MarketData[_], NotUsed]](mode match {
          case _:Backtest => _.mergeSorted(_)(MarketData.orderByTime)
          case _ => _.merge(_)
        })

        // Watch for termination of the merged data source stream and then manually close
        // the tick stream.
        .watchTermination()(Keep.right)
        .mapMaterializedValue(_.onComplete(res => {
          killSwitch.get.shutdown()
          res
        }))

        // Merge the tick stream into the main data stream.
        .map[Either[MarketData[_], Tick]](Left(_))
        .mergeMat(Source
            .actorRef[Tick](Int.MaxValue, OverflowStrategy.fail)
            .map[Either[MarketData[_], Tick]](Right(_)))(Keep.right)

        // Hookup the kill switch
        .via(killSwitch.get.flow)

        // Backtests are appended with a single tick for each exchange at the end of the
        // stream. This is to complete any outstanding requests that would otherwise not
        // be collected. This is the only time that a tick that will occur in the stream
        // for backtests. All others will be processed via the tick queue.
        .concat(mode match {
          case Backtest(range) =>
            Source(exchanges.keys.toList.map(x =>
              Right(Tick(Seq.empty, Some(x), micros = range.end))))
          case _ => Source.empty
        })

        // Lift-off
        .scan(new Session()) { case (session, dataOrTick) =>
          // Dequeue and process ticks while they occur before the current
          // market data item.
          var queueItems = dequeueTicksFor(dataOrTick)
          var tmpSession = session
          while (queueItems.nonEmpty) {
            tmpSession = queueItems.foldLeft(tmpSession)(processDataOrTick)
            queueItems = dequeueTicksFor(dataOrTick)
          }

          // And finally process the actual stream item.
          processDataOrTick(tmpSession, dataOrTick)
        }
        .drop(1)
        .toMat(Sink.foreach { s: Session => })(Keep.both)
        .run

      tickRefOpt = Some(tickRef)

      fut.onComplete {
        case Success(_) =>
          log.debug("session success")
          sessionEventsRef ! SessionComplete(error = None)
          context.stop(self)
        case Failure(err) =>
          log.error(err, "session failed")
          sessionEventsRef ! SessionComplete(error = Some(ReportError(err)))
          context.stop(self)
      }

      // Return the session id
      sessionId
  }

  override def receive: Receive = {

    case SessionPing =>
      sender ! SessionPong

    case StopSession =>
      killSwitch.foreach { killSwitch =>
        log.debug("Shutting down session")
        killSwitch.shutdown()
      }

    case StartSession =>
      val origSender = sender
      setup().onComplete {
        case Success(sessionSetup) =>
          log.debug("Session setup success")
          origSender ! (sessionSetup.sessionId, sessionSetup.sessionMicros)
          runSession(sessionSetup)
        case Failure(err) =>
          log.error(err, "Session setup failure")
          origSender ! err
      }
  }
}

object TradingSessionActor {
  case object StopSession
  case object StartSession
  case object SessionPing
  case object SessionPong

  val streamOrdering = new Ordering[Either[MarketData[_], Tick]] {
    override def compare(x: Either[MarketData[_], Tick],
                         y: Either[MarketData[_], Tick]) = (x, y) match {
      case (Left(_), Right(_)) => 1
      case (Right(_), Left(_)) => -1
      case _ => 0
    }
  }
}
