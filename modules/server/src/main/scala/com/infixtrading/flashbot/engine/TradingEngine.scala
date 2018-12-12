package com.infixtrading.flashbot.engine

import akka.Done
import akka.actor.{ActorLogging, ActorRef, ActorSystem, Props}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent._
import akka.pattern.{ask, pipe}
import akka.persistence._
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
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
import com.infixtrading.flashbot.engine.TradingSession._
import com.infixtrading.flashbot.models._
import com.infixtrading.flashbot.models.api._
import com.infixtrading.flashbot.models.core.{Account, Market, Portfolio, TimeRange}
import com.infixtrading.flashbot.report.ReportEvent.{BalanceEvent, PositionEvent}
import com.infixtrading.flashbot.report._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(strategyClassNames: Map[String, String],
                    exchangeConfigs: Map[String, ExchangeConfig],
                    defaultBots: Map[String, BotConfig],
                    dataServer: ActorRef)
  extends PersistentActor with ActorLogging {

  import TradingEngine._
  import scala.collection.immutable.Seq

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = buildMaterializer
  implicit val ec: ExecutionContext = system.dispatcher

  val snapshotInterval = 100000
  var state = TradingEngineState(Map.empty)

  override def persistenceId: String = "trading-engine"

  /**
    * Turns an incoming command into a sequence of [[TradingEngineEvent]] objects that affect the
    * state in some way and are then persisted, or into an [[EngineError]] to be returned to the
    * sender. Note that while this is an asynchronous operation (returns a Future), the thread that
    * handles engine commands will block on the Future in order to know what events to persist.
    * This only applies to commands. Queries, which are read-only, bypass Akka persistence and
    * hence are free to be fully-async.
    */
  def processCommand(command: TradingEngineCommand): Future[Seq[TradingEngineEvent]] = command match {

    case StartEngine =>

      // Start the default bots
      defaultBots.foreach {
        case (name, BotConfig(strategy, mode, paramsOpt, initial_assets, initial_positions)) =>

          val params = paramsOpt.getOrElse(json"{}")
          val initialAssets = initial_assets.getOrElse(Map.empty)
            .map(kv => Account.parse(kv._1) -> kv._2)
          val initialPositions = initial_positions.getOrElse(Map.empty)
            .map(kv => Market.parse(kv._1) -> kv._2)

          // First of all, we look for any previous sessions for this bot. If one exists, then
          // take the portfolio from the last session as the initial portfolio for this session.
          // Otherwise, use the initial_assets and initial_positions from the bot config.
          val initialSessionPortfolio =
            state.bots.get(name).map(_.last.portfolio)
              .getOrElse(Portfolio(initialAssets, initialPositions))

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

          self ! StartTradingSession(
            Some(name),
            strategy,
            params,
            TradingSessionMode(mode),
            ref,
            initialSessionPortfolio,
            Report.empty(strategy, params)
          )
      }
      val bots: Seq[String] =
        defaultBots.foldLeft(Seq.empty[String])((memo, item) => memo :+ item._1)
      Future.successful(EngineStarted(currentTimeMicros, bots) :: Nil)

    /**
      * A wrapper around a new SessionActor, which runs the actual strategy. This may be initiated
      * by either a backtest query or a running bot.
      */
    case StartTradingSession(
        botIdOpt,
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances,
        initialReport
    ) =>

      val sessionActor = context.actorOf(Props(new TradingSessionActor(
        strategyClassNames,
        exchangeConfigs,
        strategyKey,
        strategyParams,
        mode,
        sessionEventsRef,
        initialBalances,
        initialReport,
        dataServer
      )))

      // Start the session. We are only waiting for an initialization error, or a confirmation
      // that the session was started, so we don't wait for too long.
      implicit val timeout: Timeout = Timeout(10 seconds)
      (sessionActor ? "start").map {
        case (sessionId: String, micros: Long) =>
          Seq(SessionStarted(sessionId, botIdOpt, strategyKey, strategyParams,
            mode, micros, initialBalances, initialReport))
      }

    /**
      * A bot session emitted a ReportEvent. Here is where we decide what to do about it by
      * emitting the ReportDeltas that we'd like to persist in state. Specifically, if there
      * is a balance event, we want to save that to state. In addition to that, we always
      * generate report deltas and save those.
      */
    case ProcessBotSessionEvent(botId, event) =>
      if (!state.bots.isDefinedAt(botId)) {
        log.warning(s"Ignoring session event for non-existent bot $botId. $event")
        return Future.successful(Seq.empty)
      }

      val deltas = state.bots(botId).last.report.genDeltas(event)
        .map(ReportUpdated(botId, _))
        .toList

      Future.successful(event match {
        case BalanceEvent(account, balance, micros) =>
          BalancesUpdated(botId, account, balance) :: deltas
        case PositionEvent(market, position, micros) =>
          PositionUpdated(botId, market, position) :: deltas
        case _ => deltas
      })
  }

  /**
    * The TradingEngine message handler. This is how the outside world interacts with it. First
    * we match on supported message types for PersistentActor management and reponding to queries.
    * Finally we match on supported engine Commands which are processed *synchronously*. The events
    * resulting from processing the command are persisted and can be used to replay the state of
    * the actor after a crash/restart.
    */
  override def receiveCommand: Receive = {
    case err: EngineError =>
      log.error(err, "Uncaught EngineError")

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

    case query: Query => query match {
      case Ping =>
        sender ! Pong

      case BotSessionsQuery(id) =>
        sender ! state.bots.get(id)
          .map(sessions => BotSessionsResponse(id, sessions))
          .getOrElse(EngineError("Bot not found"))

      case BotReportQuery(id) =>
        sender ! state.bots.get(id)
          .map(sessions => BotResponse(id, sessions.map(_.report)))
          .getOrElse(EngineError("Bot not found"))

      case BotReportsQuery() =>
        sender ! BotsResponse(bots = state.bots.map { case (id, bot) =>
          BotResponse(id, bot.map(_.report))
        }.toSeq)

      case StrategiesQuery() =>
        sender ! StrategiesResponse(strategyClassNames.keys.map(StrategyResponse).toList)

      case StrategyInfoQuery(name) =>
        val sessionLoader = new SessionLoader(exchangeConfigs, dataServer)
        (for {
          className <- strategyClassNames.get(name).toFut(s"Unknown strategy $name.")
          strategy <- Future.fromTry(sessionLoader.loadNewStrategy(className))
          title = strategy.title
          info <- strategy.info(sessionLoader)
        } yield StrategyInfoResponse(title, name, info)) pipeTo sender

      /**
        * To resolve a backtest query, we start a trading session in Backtest mode and collect
        * all session events into a stream that we fold over to create a report.
        */
      case BacktestQuery(strategyName, params, timeRange, portfolioStr, barSize, eventsOut) =>

        try {

          // TODO: Handle parse errors
          val paramsJson = parse(params).right.get
          val report = Report.empty(strategyName, paramsJson, barSize)

          val portfolio = parseJson[Portfolio](portfolioStr).right.get

          // Fold the empty report over the ReportEvents emitted from the session.
          val (ref: ActorRef, fut: Future[Report]) =
            Source.actorRef[ReportEvent](Int.MaxValue, OverflowStrategy.fail)
              .scan[(Report, scala.Seq[Json])]((report, Seq.empty))((r, ev) => {
                implicit var newReport = r._1
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
              .toMat(Sink.last)(Keep.both)
              .run

          // Always send the initial report back to let the client know we started the backtest.
          eventsOut.foreach(_ ! report)

          // Start the trading session
          processCommand(StartTradingSession(None, strategyName, paramsJson,
              Backtest(timeRange), ref, portfolio, report)) onComplete {
            case Success(events: Seq[TradingEngineEvent]) =>
              events.foreach(println)
            case Failure(err) =>
              eventsOut.foreach(_ ! err)
          }

          fut.map(ReportResponse) pipeTo sender
        } catch {
          case err: Throwable =>
            log.error("Uncaught error during backtesting", err)
            throw err
        }

      case q => sender ! EngineError(s"Unsupported query $q")
    }

    case cmd: TradingEngineCommand =>
      // Blocking!
      val result = Await.ready(processCommand(cmd), 10 seconds).value.get
      result match {
        case Success(events) =>
          persistAll(events) { e =>
            state = state.update(e)
            if (lastSequenceNr % snapshotInterval == 0) {
              saveSnapshot(state)
            }
          }
        case Failure(err) =>
          sender ! err
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
}

object TradingEngine {

  sealed trait Query
  case object Ping extends Query
  case class BacktestQuery(strategyName: String,
                           params: String,
                           timeRange: TimeRange,
                           portfolio: String,
                           barSize: Option[Duration],
                           eventsOut: Option[ActorRef] = None) extends Query

  case class BotReportQuery(botId: String) extends Query
  case class BotReportsQuery() extends Query
  case class BotSessionsQuery(botId: String) extends Query
  case class StrategiesQuery() extends Query
  case class StrategyInfoQuery(name: String) extends Query

  sealed trait Response
  case object Pong extends Response {
    override def toString: String = "pong"
  }
  case class ReportResponse(report: Report) extends Response
  case class BotResponse(id: String, reports: Seq[Report]) extends Response
  case class BotsResponse(bots: Seq[BotResponse]) extends Response
  case class BotSessionsResponse(id: String, sessions: Seq[TradingSessionState]) extends Response
  case class StrategyResponse(name: String) extends Response
  case class StrategiesResponse(strats: Seq[StrategyResponse]) extends Response
  case class StrategyInfoResponse(title: String, key: String,
                                  info: Option[StrategyInfo]) extends Response

  final case class EngineError(message: String, cause: Option[Throwable] = None)
    extends Exception(message, cause.orNull) with Response

}
