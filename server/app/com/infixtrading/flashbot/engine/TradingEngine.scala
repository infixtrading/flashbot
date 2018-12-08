package com.infixtrading.flashbot.engine

import akka.actor.{ActorLogging, ActorRef, ActorSystem}
import akka.persistence.PersistentActor
import akka.stream.ActorMaterializer
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.util
import com.infixtrading.flashbot.core.Exchange.ExchangeConfig
import com.infixtrading.flashbot.engine.TradingSession.TradingSessionState
import io.circe.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/**
  * Creates and runs bots concurrently by instantiating strategies, loads data sources, handles
  * logging, errors, validation, bot monitoring, order execution, and persistence.
  */
class TradingEngine(strategyClassNames: Map[String, String],
                    exchangeConfigs: Map[String, ExchangeConfig],
                    defaultBots: Map[String, BotConfig])
  extends PersistentActor with ActorLogging {

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = util.stream.buildMaterializer
  implicit val ec: ExecutionContext = system.dispatcher

  override def persistenceId = "trading-engine"

  override def receiveCommand = {
    case _ => println("command")
  }

  override def receiveRecover = {
    case _ => println("recover")
  }

}


object TradingEngine {


  case class EngineState(bots: Map[String, Seq[TradingSessionState]],
                         startedAtMicros: Long = 0) {
    /**
      * A pure function that updates the state in response to an event that occurred in the
      * engine. No side effects or outside state please!
      */
    def update(event: Event): EngineState = event match {
      case EngineStarted(micros, withBots) =>
        copy(
          startedAtMicros = micros,
          bots = withBots.map(botId => botId -> Seq.empty[TradingSessionState]).toMap ++ bots
        )

      case SessionStarted(id, Some(botId), strategyKey, strategyParams, mode,
      micros, portfolio, report) =>
        copy(bots = bots + (botId -> (
          bots.getOrElse[Seq[TradingSessionState]](botId, Seq.empty) :+
            TradingSessionState(id, strategyKey, strategyParams, mode, micros, portfolio, report))))

      case e: SessionUpdated => e match {
        case ReportUpdated(botId, delta) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.updateReport(delta))))

        case BalancesUpdated(botId, account, balance) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.copy(portfolio = bot.last.portfolio.withBalance(account, balance)))))

        case PositionUpdated(botId, market, position) =>
          val bot = bots(botId)
          copy(bots = bots + (botId -> bot.updated(bot.length - 1,
            bot.last.copy(portfolio = bot.last.portfolio.unsafeSetPosition(market, position)))))

      }
    }
  }


  sealed trait Command
  case class StartTradingSession(botId: Option[String],
                                 strategyKey: String,
                                 strategyParams: Json,
                                 mode: TradingSession.Mode,
                                 sessionEvents: ActorRef,
                                 initialPortfolio: Portfolio,
                                 report: Report) extends Command

  case object StartEngine extends Command
  case class ProcessBotSessionEvent(botId: String, event: ReportEvent) extends Command

  sealed trait Event
  case class SessionStarted(id: String,
                            botId: Option[String],
                            strategyKey: String,
                            strategyParams: Json,
                            mode: TradingSession.Mode,
                            micros: Long,
                            portfolio: Portfolio,
                            report: Report) extends Event
  case class EngineStarted(micros: Long, withBots: Seq[String]) extends Event

  sealed trait SessionUpdated extends Event {
    def botId: String
  }
  case class ReportUpdated(botId: String,
                           delta: ReportDelta) extends SessionUpdated
  case class BalancesUpdated(botId: String,
                             account: Account,
                             balance: Double) extends SessionUpdated
  case class PositionUpdated(botId: String,
                             market: Market,
                             position: Position) extends SessionUpdated

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
//  case class StrategyInfoResponse(title: String, key: String,
//                                  info: Option[StrategyInfo]) extends Response

  final case class EngineError(message: String, cause: Option[Throwable] = None)
    extends Exception(message, cause.orNull) with Response

}
