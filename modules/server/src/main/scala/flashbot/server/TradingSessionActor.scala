package flashbot.server

import java.util
import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream._
import breeze.stats.mode
import io.circe._
import io.circe.syntax._
import flashbot.util.stream._
import flashbot.util._
import flashbot.util.time.currentTimeMicros
import flashbot.core.{Strategy, _}
import flashbot.models.api._
import flashbot.models.core.OrderCommand._
import flashbot.models.core._
import flashbot.core.Report.ReportError
import flashbot.core.ReportEvent._
import flashbot.core.TradingSession._

import scala.collection.{mutable => m}
import scala.concurrent.{ExecutionContext, Future, SyncVar}
import scala.util.{Failure, Success, Try}
import de.sciss.fingertree._
import flashbot.models.api.OrderCommand.{CancelOrderCmd, SubmitOrderCmd, SubmitOrderCommand}
import flashbot.models.core.Order.Buy

class TradingSessionActor(session: TradingSession) extends Actor with ActorLogging {

  import TradingSessionActor._

  implicit val system: ActorSystem = context.system
  implicit val mat: ActorMaterializer = buildMaterializer()
  implicit val ec: ExecutionContext = system.dispatcher

  // Setup a thread safe reference to an event buffer which allows the session to process
  // events synchronously when possible. This needs to be mutable buffer because we'll be
  // using reference equality with it later.
//  val eventBuffer: SyncVar[m.Buffer[Any]] = new SyncVar[m.Buffer[Any]]

  // In backtest mode we use a tick queue to simulate ticks in the stream's scan function
  // instead of using the `tickRef`. This is because the `tickRef` is only useful for ticks
  // to trigger the scan function for real time purposes. Otherwise, in backtests, we'd like to
  // ensure proper ordering of ticks in relation to incoming market data.
  // TODO: Should this be a PriorityQueue?
  implicit val tickOrderBy = (tick: Tick) => tick.micros
  var tickQueue = OrderedSeq.empty[Tick, Long]

  // Helper function for processing queued up ticks when scanning over market data.
  def dequeueTicksFor(item: Either[MarketData[_], Tick]): Seq[Either[MarketData[_], Tick]] = {
    val tickSeq = tickQueue.iterator.toList
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

//  def processSessionEvent(session: TradingSession, exchange: String, ev: Any) = ev match {
    // Send report events to the events actor ref.
//    case reportEvent: ReportEvent =>
//      val re = reportEvent match {
//        case ce: CandleEvent => ce match {
//          case ev: CandleAdd => ev.copy(series = ev.series)
//          case ev: CandleUpdate => ev.copy(series = ev.series)
//        }
//        case otherReportEvent => otherReportEvent
//      }
//      sessionEventsRef ! re

//    case cmd: OrderCommand => cmd match {
//      // When an order is to be submitted, create futures for each of the callback types.
//      // These futures will execute when their corresponding promise is completed in the session.
//      case SubmitOrderCmd(id, node) => node match {
//      }
//
//      // When an order is to be cancelled.
//      case CancelOrderCmd(id) =>
//    }
//
//    case LogMessage(msg) =>
//      log.info(msg)
//  }

  /**
    * Sends market data to the strategy.
    */
  def processData(session: TradingSession, data: MarketData[_]): Unit = {
  }

  /**
    *
    */
  def processTick(session: TradingSession, tick: Tick): Unit = {
  }


  def runSession(sessionSetup: SessionSetup): String = sessionSetup match {
    case SessionSetup(_instruments, _exchanges, strategy, sessionId, streams, sessionMicros) =>
      implicit val conversions = GraphConversions
      val priceIndex = new JPriceIndex()

      ServerMetrics.observe("streams_per_trading_session", streams.size)

      def invokeCallback(): Unit = {

      }

      def processDataOrTick(session: TradingSession,
                            dataOrTick: Either[MarketData[_], Tick]): Unit = {

        // First, setup the event buffer so that we can handle synchronous events.
//        val thisEventBuffer: m.Buffer[Any] = m.ArrayBuffer.empty
//        eventBuffer.put(thisEventBuffer)

        // Set the session `sendFn` function. Close over `thisEventBuffer` and check that it
        // matches the one in the syncvar. Only then can we append to it, otherwise, emit it
        // as an async tick.
//        session.sendFn = (events: m.Buffer[Any]) => {
//          if (eventBuffer.isSet) {
//            val buf = eventBuffer.get(5L)
//            // Check the syncvar for reference equality with our buffer.
//            if (buf.isDefined && (buf.get eq thisEventBuffer)) {
//              thisEventBuffer ++= events
//            } else {
//              emitTick(Tick(events, None, -1))
//            }
//          } else {
//            emitTick(Tick(events, None, -1))
//          }
//        }

        implicit val ctx: TradingSession = session
        implicit val idx: InstrumentIndex = ctx.instruments
        implicit val metrics: Metrics = ServerMetrics

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
            portfolioRef.update(session,
              _.initializePositions(session.prices, session.instruments, metrics))

          case _ =>
        }

        // Update the relevant exchange with the market data to collect fills and user data
        val (fills, userData, errors) = exchange
          .map(_.collect(session, data, tick))
          .getOrElse((emptyFills, emptyUserData, emptyExchangeErrors))

        // Process errors emitted from the exchange.
        for (err <- errors) {
          err match {
            case OrderRejected(req, reason) =>
//              session.orderManagers.get(ex.get).orderRejected(req.clientOid)
              session.cmdQueues.get(ex.get).closeActive()
          }
          strategy.handleEvent(err)
        }

        userData.foreach { ud =>
          // Send all user data to strategy.
          strategy.handleEvent(ud)

          val cmdQueue = session.cmdQueues.get(ex.get)
          ud match {
            /**
              * Either market or limit order received by the exchange. Associate the client id
              * with the order id assigned by the exchange. Do not close any commands yet.
              * If it's a marker order command, wait until it's done. If it's a limit order,
              * wait until it's either open or done.
              */
            case OrderReceived(id, _, clientId, _) =>
              session.newOrderId(clientId.get, id)
//              if (targetOpt.isDefined)
//                orderManager.receivedOrder(clientId.get, id)

            /**
              * Limit order is opened on the exchange. Close the active cmd that submitted it.
              */
            case ev @ OrderOpen(id, product, price, size, side) =>
              val market = Market(ex.get, product)
              portfolioRef.update(session,
                _.addOrder(Some(id), market, if (side == Buy) size else -size, price))
              cmdQueue.closeActive()
//              if (targetOpt.isDefined) {
//                orderManager.openOrder(ev)
//                closeActionForOrderId(cmdQueue, orderManager.ids, id)
//              }

            /**
              * Either market or limit order is done. Could be due to a fill, or a cancel.
              * Disassociate the ids to keep memory bounded in the ids manager. Also close
              * the command for the order id, if it exists. It will exist if this is market
              * order, an immediately filled limit order, or an order cancel. It will not
              * exist if it's a fill for an already open limit order.
              */
            case OrderDone(id, product, _, _, _, _) =>
//              val targetOpt = orderManager.ids.actualToTarget.get(id)
              val market = Market(ex.get, product)
              portfolioRef.update(session, _.removeOrder(market, id))
              cmdQueue.closeActiveForOrderId(id)
              session.rmOrderId(id)
//              if (targetOpt.isDefined) {
//                orderManager.orderComplete(id)
//                closeActionForOrderId(cmdQueue, orderManager.ids, id)
//              }
          }
        }

        // Process fills from the exchange.
        fills.foreach { fill =>
          sessionEventsRef ! CollectionEvent("fill_size", fill.size.asJson)

          val instrument = instruments(ex.get, fill.instrument)
          val market = Market(ex.get, instrument.symbol)

          // Execute the fill on the portfolio
          session.getPortfolio.fillOrder(market, fill)(instruments, session.exchangeParams)

          // Emit a trade event when we see a fill
          sessionEventsRef ! TradeEvent(
            fill.tradeId, ex.get, fill.instrument.toString,
            fill.micros, fill.price, fill.size
          )
        }

        // Call aroundHandleData and catch user errors.
        if (data.isDefined) {
          val timer = ServerMetrics.startTimer("handle_data_ms", Map("strategy" -> strategy.title))
          try {
            strategy.aroundHandleData(data.get)(session)
          } catch {
            case e: Throwable =>
              ServerMetrics.inc("handle_data_error")
              log.error(e, "Handle data error")
          } finally {
            timer.close()
          }
        }

        // Process the events. First from the tick, then from this event buffer.
        // Take our event buffer from the syncvar. This allows the next scan iteration
        // to `put` theirs in. Otherwise, `put` will block.
        if (tick.isDefined) {
          tick.get.events.foreach(e => processSessionEvent(session, ex.get, e))
        }
        eventBuffer.take().foreach(e => processSessionEvent(session, ex.get, e))

        // Here is where we tell the exchanges to do stuff, like place or cancel orders.
        if (ex.isDefined) {
          val cmdQueue = session.cmdQueues.get(ex)
          val cmd = cmdQueue.activateNext()
          if (cmd.isDefined) {
            cmd.get match {
              case cmd: PostOrderCommand =>
//                orderManager.initCreateOrder(targetId, clientId, action)
                exchange.get._order(cmd)

              case CancelLimitOrder(id) =>
                // If the id is a client id, turn it into it's corresponding order id.
                val orderIdFromClient = session.clientIdToOrderId.get(ex.get).get(id)
                if (orderIdFromClient != null)
                  exchange.get._cancel(orderIdFromClient, ???)
                else
                  exchange.get._cancel(id, ???)
            }
          }
        }
      }




      // Trigger the kill switch when data streams complete.
      dataStreamsDone.onComplete(_ => killSwitch.get.shutdown())

      val inputSrc =
        if (mode.isBacktest) dataSrc
        else dataSrc.

        // Merge the tick stream into the main data stream.
//        .mergeSortedMat(Source.actorRef[Tick](Int.MaxValue, OverflowStrategy.fail))(Keep.right)
//
//        // Hookup the kill switch
//        .via(killSwitch.get.flow)
//
//        // Backtests are appended with a single tick for each exchange at the end of the
//        // stream. This is to complete any outstanding requests that would otherwise not
//        // be collected. This is the only time that a tick that will occur in the stream
//        // for backtests. All others will be processed via the tick queue.
//        .concat(mode match {
//          case Backtest(range) =>
//            Source(exchanges.keys.toList.map(x =>
//              Right(Tick(Seq.empty, Some(x), micros = range.end))))
//          case _ => Source.empty
//        })
//        .preMaterialize()

      val session = new TradingSession()

      // Lift-off
      src.runForeach { dataOrTick =>
        // Increment the weak ref.
        session.ref = session.ref + 1

        // Dequeue and process ticks while they occur before the current
        // market data item.
        var queueItems = dequeueTicksFor(dataOrTick)
        while (queueItems.nonEmpty) {
          queueItems.foreach(processDataOrTick(session, _))
          queueItems = dequeueTicksFor(dataOrTick)
        }

        // And finally process the actual stream item.
        processDataOrTick(session, dataOrTick)

        session
      }

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

  type IDMap = java.util.HashMap[String, java.util.HashMap[String, String]]

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

  private val emptyFills = List.empty[Order.Fill]
  private val emptyUserData = List.empty[OrderEvent]
  private val emptyExchangeErrors = List.empty[ExchangeError]
}
