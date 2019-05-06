package flashbot.core

import java.time.Instant

import akka.Done
import akka.actor.Status.Status
import akka.actor.{ActorLogging, ActorRef, ActorSystem, PoisonPill, Props, Status}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import akka.http.scaladsl.Http
import akka.pattern.{ask, pipe}
import akka.persistence._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import flashbot.client.FlashbotClient
import flashbot.core.DataType.CandlesType
import flashbot.core.FlashbotConfig.{BotConfig, ExchangeConfig, GrafanaConfig, StaticBotsConfig}
import flashbot.core.ReportEvent._
import flashbot.models._
import flashbot.server._
import flashbot.strategies.TimeSeriesStrategy
import flashbot.util._
import flashbot.util.stream._
import flashbot.util.time.{FlashbotTimeout, currentTimeMicros}
import io.circe.Json
import io.circe.syntax._

import scala.collection.immutable
import scala.concurrent._
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(engineId: String,
                    strategyClassNames: Map[String, String],
                    exchangeConfigs: Map[String, ExchangeConfig],
                    staticBotsConfig: StaticBotsConfig,
                    dataServerInfo: Either[ActorRef, Props],
                    grafana: GrafanaConfig)
    extends PersistentActor with ActorLogging {

  private implicit val system: ActorSystem = context.system
  private implicit val ec: ExecutionContextExecutor = context.dispatcher
  private implicit val mat: Materializer = buildMaterializer()
  private implicit val timeout: Timeout = FlashbotTimeout.default

  override def persistenceId: String = engineId
  private val snapshotInterval = 100000

  val dataServer = dataServerInfo.left.getOrElse(context.actorOf(dataServerInfo.right.get))

  /**
    * The portfolio instance which represents your actual balances on all configured exchanges.
    * This isn't persisted in [[TradingEngineState]] for two reasons: One is that this is the only
    * case of shared state between trading sessions, and it would be great if sessions don't have
    * to block on disk IO in order to safely update it. The other reason is that we can get away
    * with it because the global portfolio must be synced from the exchanges on engine startup
    * anyway, which means we wouldn't get value out of Akka persistence in the first place.
    */
  val globalPortfolio = new SyncVar[Portfolio]
  private def isInitialized = globalPortfolio.isSet

  /**
    * This is the Akka Persistence managed state, which is why this shared state doesn't use a
    * SyncVar.
    */
  var state = TradingEngineState()

  var botSessions = Map.empty[String, TradingSession]
  var subscriptions = Map.empty[String, Set[ActorRef]]

  system.scheduler.schedule(200 millis, 200 millis, self, EngineTick)

  log.debug(s"About to initialize TradingEngine $engineId")

  /**
    * Initialization.
    */

  // Must be instantiated above the call to `startEngine`.
  implicit val loader = new EngineLoader(() => exchangeConfigs.map {
    case (name, _) => name -> configForExchange(name).get
  }, dataServer, strategyClassNames: Map[String, String])

  val (bootRsp: EngineStarted, bootEvents: Seq[TradingEngineEvent]) =
    Await.result(startEngine, timeout.duration)

  log.info("TradingEngine '{}' started at {}", engineId, bootRsp.micros)
  bootEvents.foreach(log.debug("Boot event: {}", _))

  // Start the Grafana data source server if the dataSourcePort is defined.
  if (grafana.dataSource) {
    Http().bindAndHandle(GrafanaServer.routes(new FlashbotClient(self, skipTouch = true)),
      "localhost", grafana.dataSourcePort)
  }

  // Start the Grafana manageer if the API key is defined.
  if (grafana.apiKey.isDefined) {
    context.actorOf(Props(new GrafanaManager(grafana.host, grafana.apiKey.get,
      grafana.dataSourcePort, loader)))
  }

  self ! BootEvents(bootEvents)

  // Subscribe to cluster MemberUp events to register ourselves with newly connected clients.
  val cluster: Option[Cluster] =
    if (context.system.hasExtension(Cluster)) Some(Cluster(context.system)) else None

  override def preStart(): Unit = {
    if (cluster.isDefined) {
      cluster.get.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
    }
  }
  override def postStop(): Unit = {
    if (cluster.isDefined) cluster.get.unsubscribe(self)
  }

  def startEngine: Future[(EngineStarted, Seq[TradingEngineEvent])] = {
    log.debug("Starting engine")
    for {
      fetchedPortfolio <- Future.sequence(loader.exchanges.map(name =>
          fetchPortfolio(name).transform {
            case Success(value) => Success(Some(value))
            case Failure(err) =>
              log.error(err, "Error loading portfolio for {}", name)
              Success(None)
          }))
        // Merge individual portfolios into one. Ignore failed portfolio fetches.
        .map(_.collect {
          case Some(value: Portfolio) => value
        }.foldLeft(Portfolio.empty)(_ merge _))

      // Set it in-memory portfolio state
      _ = { globalPortfolio.put(fetchedPortfolio) }

      engineStartMicros = currentTimeMicros

      // Start the "enabled" bots.
      // TODO: Notify of any bots that failed to start a session.
      sessionStartEvents: Map[String, Seq[TradingEngineEvent]] <- {
        val staticEnabledKeys = staticBotsConfig.enabledConfigs.keySet
        val dynamicEnabledKeys = state.bots.filter(_._2.enabled).keySet

        for {
          // Static bots must emit BotConfigured, BotEnabled, and SessionInitialized events when they
          // are started on boot.
          staticStartedEvents <- Future.sequence(staticEnabledKeys.map(startBot))
              .map(staticEnabledKeys zip _.map {
                case startedEv @ SessionInitialized(_, Some(botId), _, _, _, _, _) =>
                  Seq(
                    BotConfigured(engineStartMicros, botId, staticBotsConfig.configs(botId)),
                    BotEnabled(botId),
                    startedEv
                  )
              }).map(_.toMap)

          // Dynamic bots on the other hand simply emit SessionInitialized events on boot.
          dynamicStartedEvents <- Future.sequence(dynamicEnabledKeys.map(startBot(_).map(Seq(_))))
            .map(dynamicEnabledKeys zip _).map(_.toMap)
        } yield staticStartedEvents ++ dynamicStartedEvents
      }
      (keys, events) = sessionStartEvents.unzip
      engineStarted = EngineStarted(engineStartMicros)

    } yield (engineStarted, engineStarted +: events.toSeq.flatten)
  }

  /**
    * Turns an incoming command into a sequence of [[TradingEngineEvent]] objects that affect the
    * state in some way and are then persisted. Note that while this is an asynchronous operation
    * (returns a Future), the thread that handles engine commands will block on the Future in order
    * persist the events. This only applies to commands. Queries, which are read-only, bypass Akka
    * persistence and hence are free to be fully-async.
    */
  def processCommand(command: TradingEngineCommand, now: Instant)
  : Future[(Any, Seq[TradingEngineEvent])] = command match {

    /**
      * Pass through boot events. This is a special case for initialization.
      */
    case BootEvents(events) =>
      log.debug("Passing through boot events: {}", events)
      Future.successful((Done, events))

    /**
      * A bot session emitted a ReportEvent.
      */
    case ProcessBotReportEvent(botId, event) =>
      if (!state.bots.isDefinedAt(botId)) {
        log.warning(s"Ignoring session event for non-existent bot $botId. $event")
        return Future.successful((Done, Seq.empty))
      }

      // Update the report and publish to subscribers
      val currentReport = state.bots(botId).sessions.last.report
      val newReport = currentReport.update(event)
      publish(botId, newReport)

      // Clean up and shutdown session when it completes for any reason.
      val doneEvents: List[BotDisabled] = event match {
        case SessionSuccess =>
          shutdownBotSession(botId)
          List(BotDisabled(botId))
        case SessionFailure(err) =>
          shutdownBotSession(botId)
          List(BotDisabled(botId))
        case _ =>
          List()
      }

      Future.successful((Done, ReportUpdated(botId, event) :: doneEvents))

    /**
      * TODO: Check that the bot is not running. Cannot configure a running bot.
      */
    case ConfigureBot(id, botConfig) =>
      Future.successful((Done, Seq(BotConfigured(currentTimeMicros, id, botConfig))))

    case BotHeartbeat(id) =>
      Future.successful((Done, Seq(BotHeartbeatEvent(id, now.toEpochMilli * 1000))))

    case EnableBot(id) =>
      state.bots.get(id) match {
        case None => Future.failed(new IllegalArgumentException(s"Unknown bot $id"))
        case Some(bot) if bot.enabled =>
          Future.failed(new IllegalArgumentException(s"Bot $id is already enabled"))
        case Some(_) =>
          startBot(id).map(sessionStartedEvent =>
            (Done, Seq(BotEnabled(id), sessionStartedEvent)))
      }

    case DisableBot(id) =>
      state.bots.get(id) match {
        case None => Future.failed(new IllegalArgumentException(s"Unknown bot $id"))
        case Some(bot) if !bot.enabled =>
          Future.failed(new IllegalArgumentException(s"Bot $id is already disabled"))
        case Some(_) =>
          shutdownBotSession(id)
          Future.successful((Done, Seq(BotDisabled(id))))
      }

    /**
      * Internal periodic tick.
      */
    case EngineTick =>
      val expiredBots = state.bots.keySet -- state.expireBots(now).bots.keySet
      expiredBots.foreach(shutdownBotSession)
      Future.successful((Done, expiredBots.map(BotExpired).toSeq))
  }

  /**
    * The main message handler. This is how the outside world interacts with the engine. First
    * we match on supported message types for PersistentActor management. Then match on supported
    * query types to respond to queries using engine state. Finally we match on supported engine
    * Commands which are processed *synchronously*. The events resulting from processing the
    * command are persisted and can be used to replay the state of the actor after a crash/restart.
    */
  override def receiveCommand: Receive = {
    /**
      * Internal Akka Persistence commands
      */
    case SaveSnapshotSuccess(SnapshotMetadata(_, seqNr, _)) =>
      log.info("Snapshot saved: {}", seqNr)
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = seqNr - 1))

    case SaveSnapshotFailure(SnapshotMetadata(_, seqNr, _), cause) =>
      log.error(cause, "Failed to save snapshots: {}", seqNr)

    case DeleteSnapshotsSuccess(SnapshotSelectionCriteria(maxSequenceNr, _, _, _)) =>
      log.info("Snapshot deleted: {}", maxSequenceNr)
      deleteMessages(maxSequenceNr + 1)

    case DeleteSnapshotsFailure(SnapshotSelectionCriteria(maxSequenceNr, _, _, _), cause) =>
      log.error(cause, "Failed to delete snapshots: {}", maxSequenceNr)

    case DeleteMessagesSuccess(toSeqNr) =>
      log.info("Events deleted: {}", toSeqNr)

    case DeleteMessagesFailure(cause, toSeqNr) =>
      log.error(cause, "Failed to delete events: {}", toSeqNr)

    /**
      * Respond to queries asynchronously. They have no ability to write events to persistence.
      */
    case query: TradingEngineQuery => query match {
      case Ping =>
        sender ! Pong(bootRsp.micros)

      case BotStatusQuery(botId) =>
        botStatus(botId) pipeTo sender

      case BotSessionsQuery(id) =>
        state.bots.get(id)
          .map(bot => Future.successful(BotSessionsResponse(id, bot.sessions)))
          .getOrElse(Future.failed(new IllegalArgumentException("Bot not found"))) pipeTo sender

      case BotReportQuery(id) =>
        state.bots.get(id)
          .map(bot => Future.successful(BotResponse(id, bot.sessions.map(_.report))))
          .getOrElse(Future.failed(new IllegalArgumentException("Bot not found"))) pipeTo sender

      case BotReportsQuery() =>
        sender ! BotsResponse(bots = state.bots.map { case (id, bot) =>
          BotResponse(id, bot.sessions.map(_.report))
        }.toSeq)

      case StrategiesQuery() =>
        sender ! StrategiesResponse(strategyClassNames.keys.map(StrategyResponse).toList)

      case StrategyInfoQuery(name) =>
        (for {
          className <- strategyClassNames.get(name)
            .toFut(new IllegalArgumentException(s"Unknown strategy $name"))
          strategy <- Future.fromTry(loader.loadNewStrategy(className))
          title = strategy.title
          info <- strategy.info(loader)
        } yield StrategyInfoResponse(title, name, info)) pipeTo sender

      /**
        * Generate and respond with a [[NetworkSource]] of the [[Report]] for the specified bot.
        */
      case SubscribeToReport(botId) =>
        val fut1 = for {
          bot <- state.bots.get(botId).toFut(
            new IllegalArgumentException(s"Unknown bot $botId"))
          session <- bot.sessions.lastOption.toFut(
            new IllegalStateException(s"Bot $botId not started"))
          (ref, src) = Source
            .actorRef[Report](Int.MaxValue, OverflowStrategy.fail)
            .preMaterialize()
          _ = {
            subscriptions += (botId -> (subscriptions.getOrElse(botId, Set.empty) + ref))
            ref ! session.report
          }
        } yield src

        // Await here because there isn't anything that should actually take time in the
        // `fut1`, BUT we don't want race conditions between the subscriptions and the
        // initial report.
        val src = Await.result(fut1, 5 seconds)
        NetworkSource.build(src) pipeTo sender

      /**
        * For all configured exchanges, try to fetch the portfolio. Swallow future failures here
        * and warn the sender of the exchange specific error instead. Do not populate the global
        * portfolio with exchanges that failed to fetch.
        *
        * TODO: Warn about failures. Maybe keep an "ExchangeState" in in EngineState rather than
        *       just the params?
        */
      case SyncExchanges =>
        Future.sequence(loader.exchanges.map(name => fetchPortfolio(name).transform {
          case Success(value) => Success(Some(value))
          case Failure(_) => Success(None)
        }))
          // Merge individual portfolios into one. Ignore failed portfolio fetches.
          .map(_.collect {
            case Some(value) => value
          }.reduce(_ merge _))

          // Set the in-memory global portfolio
          .andThen {
            case Success(newGlobalPortfolio) =>
              globalPortfolio.take()
              globalPortfolio.put(newGlobalPortfolio)
          }

          // Create and send response
          .map(PortfolioResponse) pipeTo sender

      /**
        * Fetch portfolio for a single exchange. Fail the future and remove existing entries for
        * this exchange from the global portfolio if we were not able to fetch.
        */
      case SyncExchange(name) =>
        fetchPortfolio(name)
          // Set in-memory state
          .transform {
            case Success(newGlobalPortfolio) =>
              globalPortfolio.take()
              globalPortfolio.put(newGlobalPortfolio)
              Success(newGlobalPortfolio)
            case Failure(_) =>
              val gp = globalPortfolio.take()
              val newGP = gp.withoutExchange(name)
              globalPortfolio.put(newGP)
              Success(newGP)
          }
          // Create and send response
          .map(PortfolioResponse) pipeTo sender

      case query @ MarketDataIndexQuery =>
        (dataServer ? query) pipeTo sender

      /**
        * Proxy market data requests to the data server.
        */
      case req: DataStreamReq[_] =>
        val timer = ServerMetrics.startTimer("data_query_ms")
        (dataServer ? req)
          .mapTo[StreamResponse[MarketData[_]]]
          .flatMap(_.rebuild)
          .andThen { case x => timer.close() } pipeTo sender

      /**
        * A TimeSeriesQuery is a thin wrapper around a backtest of the TimeSeriesStrategy.
        */
      case query: TimeSeriesQuery =>

        (if (query.path.isPattern) Future.failed(
          new IllegalArgumentException("Patterns are not currently supported in time series queries."))
        else {

          def viaBacktest: Future[debox.Map[String, CandleFrame]] = {
            val params = TimeSeriesStrategy.Params(query.path)
            (self ? BacktestQuery("time_series", params.asJson, query.range, "", Some(query.interval)))
              .mapTo[ReportResponse]
              .map(_.report.timeSeries)
          }

          def viaDataServer(req: DataStreamReq[Candle],
                            interval: Duration): Future[Map[String, Vector[Candle]]] = for {
            rsp <- (dataServer ? req).mapTo[StreamResponse[MarketData[Candle]]]
            candlesMD <- rsp.toSource.map(_.data)
              .via(TimeSeriesTap.aggregateCandles(interval)).runWith(Sink.seq)
            path = req.selection.path
            key = List(path.source, path.topic).mkString(".")
          } yield Map(key -> candlesMD.toVector)

          query match {
            // Price queries may be served by the data server directly if candle data exists
            // for this or finer granularity.
            case PriceQuery(path, range, interval) =>
              for {
              index <- (dataServer ? MarketDataIndexQuery).mapTo[Map[Long, DataPath[Any]]]

              candlePath = path.withType(CandlesType(interval))
              exactMatch = index.values.collectFirst {
                case p: DataPath[Candle] if candlePath == p => p
              }
              finestMatch = index.values.toSeq.filter(_.matchesLocation(path))
                .map(_.datatype).collect({
                  case ct @ CandlesType(d) if d < interval => ct
                }).sortBy(_.duration).headOption.map(t => path.withType(t))
              matchedPath = exactMatch.orElse(finestMatch)
              matchedRequest = matchedPath.map(p =>
                DataStreamReq(DataSelection(p, Some(range.start), Some(range.end))))
              result <- matchedRequest.map(viaDataServer(_, interval)).getOrElse(viaBacktest)
            } yield result

            case _ =>
              viaBacktest
          }
        }) pipeTo sender()

      /**
        * To resolve a backtest query, we start a trading session in Backtest mode and collect
        * all session events into a stream that we fold over to create a report.
        */
      case BacktestQuery(strategyName, paramsJson, timeRange, portfolioStr,
          barSize, reportJsonListener, dataOverrides) =>

        val timer = ServerMetrics.startTimer("backtest_ms")

        val portfolioRef = new PortfolioRef.Isolated(portfolioStr)

        // Serialize into json before any mutation can happen to the report instance.
        // This is sent as the first message to reportJsonListener.
//        val initialReportJson = initialReport.asJson

        val (ref, reportEventSrc) = Source
          .actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
          .alsoTo(Sink.foreach { x =>
            ServerMetrics.inc("report_event_count")
          })
          .preMaterialize()

        // Helper method for sending events to actor refs.
        def emitEvent(event: Any, listenerOnly: Boolean = false): Unit = {
          if (!listenerOnly) ref ! event
          if (reportJsonListener.isDefined) reportJsonListener.get ! event
        }

        // Initialize the trading session
        val (session: TradingSession, initEvent) = tryStartTradingSession(None, strategyName, paramsJson,
          Backtest(timeRange), ref, portfolioRef, initialReport, dataOverrides)

        // Must only be accessed after the session initialization future completes, because
        // we are accessing `session.instruments` here.
        lazy val initialReport = Report.empty(
          strategyName, paramsJson, barSize.map(d => Duration.fromNanos(d.toNanos)),
          portfolioRef.getPortfolio(Some(session.instruments))
        )

        // Fold the empty report over the ReportEvents emitted from the session.
        def reportFuture(): Future[Report] = reportEventSrc
          .scan[Report](initialReport)((r, ev) => {
            // Metrics
            ServerMetrics.inc("backtest_report_event_counter")

            // Send the report event to the listener.
            emitEvent(ev, listenerOnly = true)

            // Shut down the main portfolio ref and the listener when a SessionComplete event arrives.
            ev match {
              case SessionSuccess => emitEvent(Status.Success(Done))
              case SessionFailure(err) => emitEvent(Status.Failure(err))
            }

            // Update the report we're scanning over.
            r.update(ev)
          })
          .toMat(Sink.last)(Keep.right)
          .run

        // Transform SessionInitializationError as a failure at the Future level.
        initEvent transform {
            case Success(ev: SessionInitialized) => Success(ev)
            case Success(err: SessionInitializationError) => Failure(err)
            case Failure(err) => Failure(err)

        // Always send the initial report back to let the listener know we started the backtest
        } andThen {
          case Success(_: SessionInitialized) =>
            emitEvent(initialReport, listenerOnly = true)

        // On successful initialization, build our report from the ReportEvent stream.
        } flatMap { _ =>
          reportFuture()

        // Finally, close the timer.
        } andThen { case _ =>
          timer.close()

        // Map to a ReportResponse and pipe back to sender
        } map ReportResponse pipeTo sender

      case q =>
        Future.failed(new IllegalArgumentException(s"Unsupported query: $q")) pipeTo sender
    }

    /**
      * The main command handling block.
      */
    case cmd: TradingEngineCommand =>
      val now = Instant.now
      // Blocking!
      val result = Await.ready(processCommand(cmd, now), timeout.duration).value.get
      result match {
        case Success((response, events)) =>
          val immutableSeq = events.asInstanceOf[immutable.Seq[TradingEngineEvent]]
          persistAll(immutableSeq)(persistenceCallback(now))
          sender ! response

        case Failure(err) =>
          Future.failed(err) pipeTo sender
      }
  }

  private def persistenceCallback(now: Instant) = { e: TradingEngineEvent =>
    log.debug("Persisted event {}", e)
    state = state.update(e)
    if (lastSequenceNr % snapshotInterval == 0) {
      saveSnapshot(state)
    }
  }

  /**
    * Recover persisted state after a restart or crash.
    */
  override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot: TradingEngineState) =>
      state = snapshot
    case RecoveryCompleted => // Ignore
    case event: TradingEngineEvent =>
      state = state.update(event)
  }

  private def tryStartTradingSession(botId: Option[String],
                                     strategyKey: String,
                                     strategyParams: Json,
                                     mode: TradingSessionMode,
                                     sessionEventsRef: ActorRef,
                                     portfolioRef: PortfolioRef,
                                     report: Report,
                                     dataOverrides: Seq[DataOverride[_]])
      : (TradingSession, Future[SessionInitEvent]) = {

    val session = new TradingSession(
      strategyKey,
      strategyParams,
      mode,
      dataServer,
      loader,
      log,
      report,
      system.scheduler,
      sessionEventsRef,
      portfolioRef,
      dataOverrides,
      mat,
      ec
    )

    // Start the session. We are only waiting for an initialization error, or a confirmation
    // that the session was started, so we don't wait for too long.
    val fut = session.start() transform {
      case Success(setup) =>
        log.debug("Trading session initialized")
        Success(SessionInitialized(setup.sessionId, botId, strategyKey, strategyParams,
          mode, setup.sessionMicros, report))

      case Failure(err: Exception) =>
        log.error(err, "Error during trading session init. Wrapping in a SessionInitializationError.")
        Success(SessionInitializationError(err, botId, strategyKey, strategyParams,
          mode, portfolioRef.toString, report))

      case Failure(err) =>
        log.error(err, "Fatal error during trading session init")
        Failure(err)
    }

    (session, fut)
  }

  private def startBot(name: String): Future[TradingEngineEvent] = {
    allBotConfigs(name) match {
      case BotConfig(strategy, mode, params, _, initial_assets, initial_positions) =>

        log.debug(s"Starting bot $name")

        val initialAssets = initial_assets.map(kv => Account.parse(kv._1) -> kv._2)
        val initialPositions = initial_positions.map(kv => Market.parse(kv._1) -> kv._2)

        // Build our PortfolioRef. Paper trading bots have an isolated portfolio while live bots
        // share the global one.
        val portfolioRef = mode match {
          case Paper(_) =>
            // Look for any previous sessions for this bot. If one exists, then take the
            // portfolio from the last session as the initial portfolio for this session.
            // Otherwise, use the initial_assets and initial_positions from the bot config.
            val initialSessionPortfolio =
              state.bots.get(name).flatMap(_.sessions.lastOption.map(_.report.portfolio))
                .getOrElse(Portfolio(initialAssets, initialPositions, Map.empty))
            new PortfolioRef.Isolated(initialSessionPortfolio.toString)

          case Live =>
            // Instantiate an anonymous PortfolioRef which uses the actor state in scope.
            // Use locking to prevent race conditions when updating the portfolio.
            new PortfolioRef {
              override def getPortfolio(instruments: Option[InstrumentIndex]): Portfolio = globalPortfolio.get

              override def printPortfolio: String = globalPortfolio.get.toString

              override def acquirePortfolio(ctx: TradingSession): Portfolio = globalPortfolio.take()

              override def releasePortfolio(portfolio: Portfolio): Unit = globalPortfolio.put(portfolio)
            }
        }

        // Create an actor that processes ReportEvents from this session.
        val (ref, fut) = Source
          .actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
          .toMat(Sink.foreach { event =>
            self ! ProcessBotReportEvent(name, event)
          })(Keep.both)
          .run

        fut onComplete {
          case Success(Done) =>
            // TODO: What does it mean for a bot to complete? Can they complete? Or just crash.
            log.info(s"Bot $name completed successfully")
          case Failure(err) =>
            log.error(err, s"Bot $name failed")
        }

        val (session, startFut) =
          tryStartTradingSession(Some(name), strategy, params, mode, ref, portfolioRef,
            Report.empty(strategy, params), Seq.empty)

        botSessions += (name -> session)

        startFut
    }
  }

  /**
    * Let's keep this idempotent.
    */
  def shutdownBotSession(name: String): Future[Done] = this.synchronized {
    log.info(s"Trying to shutdown session for bot $name")

    val hasSubscriptions = subscriptions.isDefinedAt(name)
    if (hasSubscriptions) {
      publish(name, Status.Success(Done))
      subscriptions -= name
    }

    val session = botSessions.get(name)
    if (session.isDefined) {
      botSessions -= name
      session.get.shutdown()
    } else {
      if (hasSubscriptions)
        log.error(s"Subscriptions detected and removed for invalid bot $name")
      Future.failed(new RuntimeException(s"$name is not a valid bot name"))
    }
  }

  def publish(id: String, reportMsg: Any): Unit = {
    subscriptions.getOrElse(id, Set.empty).foreach { ref =>
      ref ! reportMsg
    }
  }

  def pingBot(name: String): Future[Pong] = Future {
    Await.result(botSessions(name).ping, 5 seconds)
  }

  def botStatus(name: String): Future[BotStatus] = state.bots.get(name) match {
    case None =>
      if (allBotConfigs contains name) Future.successful(Disabled)
      else Future.failed(new IllegalArgumentException(s"Unknown bot $name"))
    case Some(BotState(_, true, _, _)) => pingBot(name).transform {
      case Success(Pong(_)) => Success(Running)
      case _ => Success(Crashed)
    }
    case Some(BotState(_, false, _, _)) => Future.successful(Disabled)
  }

  /**
    * Fetch portfolio data for one exchange.
    */
  private def fetchPortfolio(name: String): Future[Portfolio] =
    for {
      exchange <- loader.loadNewExchange(name).toFut
      portfolio <- exchange.fetchPortfolio
      assets = debox.Map.fromIterable(portfolio._1.map { case (k, v) => Account(name, k) -> v })
      positions = debox.Map.fromIterable(portfolio._2.map { case (k, v) => Market(name, k) -> v})
    } yield new Portfolio(assets, positions, debox.Map.empty)

  private def paramsForExchange(name: String): Option[Json] =
    state.exchanges.get(name).map(_.params).orElse(exchangeConfigs(name).params)

  private def configForExchange(name: String): Option[ExchangeConfig] =
    exchangeConfigs.get(name).map(c => c.copy(params = paramsForExchange(name)))

  private def allBotConfigs: Map[String, BotConfig] =
    staticBotsConfig.configs ++ dynamicBotConfigs

  private def dynamicBotConfigs: Map[String, BotConfig] =
    state.bots.filter(_._2.config.isDefined).mapValues(_.config.get)
}

object TradingEngine {

  def props(name: String): Props = props(name, FlashbotConfig.load())

  def props(name: String, config: FlashbotConfig): Props =
    Props(new TradingEngine(name, config.strategies, config.exchanges,
      config.bots, Right(DataServer.props(config.noIngest)), config.grafana))

  def props(name: String, config: FlashbotConfig, dataServer: ActorRef): Props =
    Props(new TradingEngine(name, config.strategies, config.exchanges,
      config.bots, Left(dataServer), config.grafana))
}
