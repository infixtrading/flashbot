package com.infixtrading.flashbot.engine

import java.security.InvalidParameterException

import akka.Done
import akka.actor.{ActorInitializationException, ActorLogging, ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.{ask, pipe}
import akka.persistence._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import com.infixtrading.flashbot
import io.circe.Json
import io.circe.syntax._
import io.circe.literal._
import io.circe.parser.parse
import com.infixtrading.flashbot.core.FlashbotConfig.{BotConfig, ExchangeConfig}
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.util.time.currentTimeMicros
import com.infixtrading.flashbot.util.stream.buildMaterializer
import com.infixtrading.flashbot.util.json._
import com.infixtrading.flashbot.util._
import com.infixtrading.flashbot.models.api._
import com.infixtrading.flashbot.models.core.{Account, Market, Portfolio, TimeRange}
import com.infixtrading.flashbot.report.ReportEvent.{BalanceEvent, PositionEvent, SessionComplete}
import com.infixtrading.flashbot.report._

import scala.concurrent.duration._
import scala.concurrent._
import scala.collection.immutable
import scala.util.{Failure, Success, Try}


/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(engineId: String,
                    strategyClassNames: Map[String, String],
                    defaultExchangeConfigs: Map[String, ExchangeConfig],
                    defaultBotConfigs: Map[String, BotConfig],
                    dataServer: ActorRef)
  extends PersistentActor with ActorLogging {

  private implicit val system: ActorSystem = context.system
  private implicit val mat: ActorMaterializer = buildMaterializer
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val timeout: Timeout = Timeout(5 seconds)

  override def persistenceId: String = "trading-engine"

  private val snapshotInterval = 100000

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

  log.debug(s"About to initialize TradingEngine $engineId")

  /**
    * Initialization.
    */

  val getExchangeConfigs = () => defaultExchangeConfigs.map {
    case (name, _) => name -> configForExchange(name).get
  }
  val (bootRsp: EngineStarted, bootEvents: Seq[TradingEngineEvent]) =
    Await.result(startEngine, timeout.duration)

  log.info("TradingEngine '{}' started at {}", engineId, bootRsp.micros)
  bootEvents.foreach(log.debug("Boot event: {}", _))

  def startEngine: Future[(EngineStarted, Seq[TradingEngineEvent])] = {
    implicit val loader = new SessionLoader(getExchangeConfigs, dataServer)
    log.debug("Starting engine")
    for {
      fetchedPortfolio <- Future.sequence(getExchangeConfigs().keys.map(name =>
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

      // Set it in-memory state
      _ = { globalPortfolio.put(fetchedPortfolio) }

      // Start the bots. Notify of any bots that failed to start a session.
      sessionStartEvents <- startBots
      (keys, events) = sessionStartEvents.unzip
      engineStarted = EngineStarted(currentTimeMicros)

    } yield (engineStarted, engineStarted +: events.toSeq)
  }

  /**
    * Turns an incoming command into a sequence of [[TradingEngineEvent]] objects that affect the
    * state in some way and are then persisted. Note that while this is an asynchronous operation
    * (returns a Future), the thread that handles engine commands will block on the Future in order
    * persist the events. This only applies to commands. Queries, which are read-only, bypass Akka
    * persistence and hence are free to be fully-async.
    */
  def processCommand(command: TradingEngineCommand)
  : Future[(Any, Seq[TradingEngineEvent])] = command match {

    /**
      * Fetch portfolios from all exchanges on start.
      * Wrap failures from individual exchanges as events.
      * TODO: Notify user of errors and/or set the ExchangeState to some error state.
      */
//    case StartEngine =>

    /**
      * A bot session emitted a ReportEvent. Here is where we decide what to do about it by
      * emitting the ReportDeltas that we'd like to persist in state. Specifically, if there
      * is a balance event, we want to save that to state. In addition to that, we always
      * generate report deltas and save those.
      */
    case ProcessBotSessionEvent(botId, event) =>
      if (!state.bots.isDefinedAt(botId)) {
        log.warning(s"Ignoring session event for non-existent bot $botId. $event")
        return Future.successful((Done, Seq.empty))
      }

      val deltas = state.bots(botId).sessions.last.report.genDeltas(event)
        .map(ReportUpdated(botId, _))
        .toList

      Future.successful(event match {
        case BalanceEvent(account, balance, micros) =>
          (Done, BalancesUpdated(botId, account, balance) :: deltas)
        case PositionEvent(market, position, micros) =>
          (Done, PositionUpdated(botId, market, position) :: deltas)
        case _ => (Done, deltas)
      })
  }

  /**
    * The main message handler. This is how the outside world interacts with the engine. First
    * we match on supported message types for PersistentActor management and reponding to queries.
    * Finally we match on supported engine Commands which are processed *synchronously*. The events
    * resulting from processing the command are persisted and can be used to replay the state of
    * the actor after a crash/restart.
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

      case BotSessionsQuery(id) =>
        state.bots.get(id)
          .map(bot => Future.successful(BotSessionsResponse(id, bot.sessions)))
          .getOrElse(Future.failed(new InvalidParameterException("Bot not found"))) pipeTo sender

      case BotReportQuery(id) =>
        state.bots.get(id)
          .map(bot => Future.successful(BotResponse(id, bot.sessions.map(_.report))))
          .getOrElse(Future.failed(new InvalidParameterException("Bot not found"))) pipeTo sender

      case BotReportsQuery() =>
        sender ! BotsResponse(bots = state.bots.map { case (id, bot) =>
          BotResponse(id, bot.sessions.map(_.report))
        }.toSeq)

      case StrategiesQuery() =>
        sender ! StrategiesResponse(strategyClassNames.keys.map(StrategyResponse).toList)

      case StrategyInfoQuery(name) =>
        val sessionLoader = new SessionLoader(getExchangeConfigs, dataServer)
        (for {
          className <- strategyClassNames.get(name).toFut(s"Unknown strategy $name.")
          strategy <- Future.fromTry(sessionLoader.loadNewStrategy(className))
          title = strategy.title
          info <- strategy.info(sessionLoader)
        } yield StrategyInfoResponse(title, name, info)) pipeTo sender

      /**
        * For all configured exchanges, try to fetch the portfolio. Swallow future failures here
        * and warn the sender of the exchange specific error instead. Do not populate the global
        * portfolio with exchanges that failed to fetch.
        *
        * TODO: Warn about failures. Maybe keep an "ExchangeState" in in EngineState rather than
        *       just the params?
        */
      case SyncExchanges =>
        implicit val loader = new SessionLoader(getExchangeConfigs, dataServer)
        Future.sequence(getExchangeConfigs().keys.map(name => fetchPortfolio(name).transform {
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
        implicit val loader = new SessionLoader(getExchangeConfigs, dataServer)
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

      /**
        * To resolve a backtest query, we start a trading session in Backtest mode and collect
        * all session events into a stream that we fold over to create a report.
        */
      case BacktestQuery(strategyName, params, timeRange, portfolioStr, barSize, eventsOut) =>

        // TODO: Remove the try catch
        try {

          // TODO: Handle parse errors
          val paramsJson = parse(params).right.get
          val report = Report.empty(strategyName, paramsJson, barSize)

          val portfolio = parseJson[Portfolio](portfolioStr).right.get

          val (ref, reportEventSrc) = Source
            .actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
            .preMaterialize()

          // Fold the empty report over the ReportEvents emitted from the session.
          val fut: Future[Report] = reportEventSrc
            .scan[(Report, scala.Seq[Json])]((report, Seq.empty))((r, ev) => {
              implicit var newReport = r._1
              // Complete this stream once a SessionComplete event comes in.
              ev match {
                case _: SessionComplete => ref ! PoisonPill
                case _ => // Ignore other events that are not relevant to stream completion
              }

              val deltas = r._1.genDeltas(ev)
              var jsonDeltas = Seq.empty[Json]
              deltas.foreach { delta =>
                jsonDeltas :+= delta.asJson
              }
              (deltas.foldLeft(r._1)(_.update(_)), jsonDeltas)
            })
            // Send the report deltas to the client if requested.
            .alsoTo(Sink.foreach(rd => {
              eventsOut.foreach(ref => rd._2.foreach(ref ! _))
            }))
            .map(_._1)
            .toMat(Sink.last)(Keep.right)
            .run

          // Always send the initial report back to let the client know we started the backtest.
          eventsOut.foreach(_ ! report)

          // Start the trading session
          startTradingSession(None, strategyName, paramsJson, Backtest(timeRange),
            ref, new flashbot.engine.PortfolioRef.Isolated(portfolio), report) onComplete {
            case Success(event: TradingEngineEvent) =>
              log.debug("Trading session started")
              eventsOut.foreach(_ ! event)
            case Failure(err) =>
              log.error(err, "Trading session initialization error")
              eventsOut.foreach(_ ! err)
          }

          fut.andThen {
            case x =>
              log.info("Fut: {}", x)
          }.map(ReportResponse) pipeTo sender
        } catch {
          case err: Throwable =>
            log.error("Uncaught error during backtesting", err)
            throw err
        }

      case q =>
        Future.failed(new InvalidParameterException(s"Unsupported query: $q")) pipeTo sender
    }

    /**
      * The main command handling block.
      */
    case cmd: TradingEngineCommand =>
      // Blocking!
      val result = Await.ready(processCommand(cmd), timeout.duration).value.get
      result match {

        case Success((response, events)) =>
          val immutableSeq = events.asInstanceOf[immutable.Seq[TradingEngineEvent]]
          persistAll(immutableSeq)(persistenceCallback)
          sender ! response

        case Failure(err) =>
          Future.failed(err) pipeTo sender
      }
  }

  private def persistenceCallback = { e: TradingEngineEvent =>
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
    case RecoveryCompleted => // ignore
    case event: TradingEngineEvent =>
      state = state.update(event)
  }

  private def startTradingSession(botId: Option[String],
                                  strategyKey: String,
                                  strategyParams: Json,
                                  mode: TradingSessionMode,
                                  sessionEventsRef: ActorRef,
                                  portfolioRef: PortfolioRef,
                                  report: Report): Future[TradingEngineEvent] = {

    val sessionActor = context.actorOf(Props(new TradingSessionActor(
      strategyClassNames,
      getExchangeConfigs,
      strategyKey,
      strategyParams,
      mode,
      sessionEventsRef,
      portfolioRef,
      report,
      dataServer
    )))

    val initialPortfolio = portfolioRef.getPortfolio

    // Start the session. We are only waiting for an initialization error, or a confirmation
    // that the session was started, so we don't wait for too long.
    log.debug("Sending start")
    (sessionActor ? "start").map[TradingEngineEvent] {
      case (sessionId: String, micros: Long) =>
        log.debug("Session started")
        SessionStarted(sessionId, botId, strategyKey, strategyParams,
          mode, micros, initialPortfolio, report)
    } recover {
      case err: Exception =>
        log.error(err, "Error during session init")
        SessionInitializationError(err, botId, strategyKey, strategyParams,
          mode, initialPortfolio, report)
    }
  }

  /**
    * TODO: We definitely want to encapsulate bots in their own actor so we can get supervision
    *       and lifecycle management for free.
    */
  private def startBot(name: String): Future[TradingEngineEvent] = {
    getBotConfigs(name) match {
      case BotConfig(strategy, modeStr, paramsOpt, initial_assets, initial_positions) =>

        val params = paramsOpt.getOrElse(json"{}")
        val initialAssets = initial_assets.getOrElse(Map.empty)
          .map(kv => Account.parse(kv._1) -> kv._2)
        val initialPositions = initial_positions.getOrElse(Map.empty)
          .map(kv => Market.parse(kv._1) -> kv._2)

        val mode = TradingSessionMode(modeStr)

        // Build our PortfolioRef. Paper trading bots have an isolated portfolio while live bots
        // share the global one.
        val portfolioRef = mode match {
          case Paper(_) =>
            // Look for any previous sessions for this bot. If one exists, then take the
            // portfolio from the last session as the initial portfolio for this session.
            // Otherwise, use the initial_assets and initial_positions from the bot config.
            val initialSessionPortfolio =
              state.bots.get(name).flatMap(_.sessions.lastOption.map(_.portfolio))
                .getOrElse(Portfolio(initialAssets, initialPositions))
            new PortfolioRef.Isolated(initialSessionPortfolio)

          case Live =>
            // Instantiate an anonymous PortfolioRef which uses the actor state in scope.
            // This object will be called concurrently by strategies running in different
            // threads, which is why we need globalPortfolio to be a SyncVar.
            new PortfolioRef {
              override def mergePortfolio(partial: Portfolio) = {
                globalPortfolio.put(globalPortfolio.take().merge(partial))
              }
              override def getPortfolio = globalPortfolio.get
            }
        }

        // Create an actor that processes ReportEvents from this session.
        val (ref, fut) = Source
          .actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
          .toMat(Sink.foreach { event =>
            self ! ProcessBotSessionEvent(name, event)
          })(Keep.both)
          .run

        fut onComplete {
          case Success(Done) =>
            // TODO: What does it mean for a bot to complete? Can they complete? Or just crash.
            log.info(s"Bot $name completed successfully")
          case Failure(err) =>
            log.error(err, s"Bot $name failed")
        }

        startTradingSession(Some(name), strategy, params, mode, ref, portfolioRef,
          Report.empty(strategy, params))
    }
  }

  def startBots: Future[Map[String, TradingEngineEvent]] = {
    val keys = getBotConfigs.keys
    Future.sequence(keys.map(startBot)).map(keys zip _).map(_.toMap)
  }

  /**
    * Fetch portfolio data for one exchange.
    */
  private def fetchPortfolio(name: String)(implicit loader: SessionLoader): Future[Portfolio] =
    for {
      exchange <- loader.loadNewExchange(name).toFut
      portfolio <- exchange.fetchPortfolio
      assets = portfolio._1.map(kv => Account(name, kv._1) -> kv._2)
      positions = portfolio._2.map(kv => Market(name, kv._1) -> kv._2)
    } yield Portfolio(assets, positions)

  private def paramsForExchange(name: String): Option[Json] =
    state.exchanges.get(name).map(_.params).orElse(defaultExchangeConfigs(name).params)

  private def configForExchange(name: String): Option[ExchangeConfig] =
    defaultExchangeConfigs.get(name).map(c => c.copy(params = paramsForExchange(name)))

  private def getBotConfigs: Map[String, BotConfig] =
    defaultBotConfigs ++ state.bots.filter(_._2.config.isDefined).mapValues(_.config.get)
}

object TradingEngine {
}
