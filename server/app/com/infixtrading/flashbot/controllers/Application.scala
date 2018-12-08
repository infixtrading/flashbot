package com.infixtrading.flashbot.controllers

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.infixtrading.flashbot.core.Control
import javax.inject._
import play.api.libs.streams.ActorFlow
import play.api.mvc._
import akka.pattern.pipe
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps


@Singleton
class Application @Inject()(cc: ControllerComponents)
                           (implicit system: ActorSystem,
                            mat: Materializer,
                            ec: ExecutionContext) extends AbstractController(cc) {

  implicit val timeout: Timeout = Timeout(5 seconds)

  def index = Action {
    Ok(views.html.index())
  }

  /**
    * Visiting the backtest page spins up a new backtest in the trading engine. First we check to
    * ensure the given strategy exists and load the page. The JS on the page creates an empty
    * Report instance and connects to the server via WebSocket. It sends the command to start the
    * trading session, and then receives report update events. It folds over the report events to
    * reconstruct and render it in React. When the backtest is over, the WebSocket is automatically
    * closed by the server.
    */
//  def backtest(strategy: String) = Action.async { request =>
//    for {
//      rsp: StrategiesResponse <- Control.request[StrategiesResponse](StrategiesQuery())
//      strat: StrategyResponse <- rsp.strats.find(_.name == strategy).toFut(s"Unknown strategy $strategy")
//      infoRsp: StrategyInfoResponse <- Control.request[StrategyInfoResponse](StrategyInfoQuery(strat.name))
//      //        .map(info => renderError(s"Strategy ${strat.name}. Page not implemented."))
//    } yield Ok(views.html.backtest_setup(infoRsp))
//  }

//  def backtestWS(strategy: String, from: String, to: String, balances: String): WebSocket =
//    WebSocket.accept[String, String] { request =>
//      ActorFlow.actorRef { out => BacktestSocket.props(strategy,
//        TimeRange.build(Instant.now, from, to), balances, out) }
//    }

  /**
    * Visiting the bot page connects to a running bot.
    */
//  def bot(bot: String) = Action.async {
//    (Control.engine.get ? TradingEngine.BotSessionsQuery(bot)).map {
//      case BotSessionsResponse(_, sessions) =>
//        Ok(views.html.bot(bot, sessions.lastOption))
//      case err: EngineError =>
//        Ok(views.html.error(err))
//    }
//  }

//  def botSocket = WebSocket.accept[String, String] { request =>
//    ActorFlow.actorRef { out => BotSocket.props(out) }
//  }

}
