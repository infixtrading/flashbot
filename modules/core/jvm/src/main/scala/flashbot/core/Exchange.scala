package flashbot.core

import java.util.UUID.randomUUID
import java.util.concurrent.ConcurrentLinkedQueue

import flashbot.models.core.FixedSize
import flashbot.models.core.Order.Fill
import flashbot.models.core._
import flashbot.util.time
import io.circe.Json

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.math.BigDecimal.RoundingMode.HALF_DOWN
import scala.util.{Failure, Success, Try}

abstract class Exchange {

  // Fees used for simulation
  def makerFee: Double
  def takerFee: Double

  // Exchange API request implementations
  def order(req: OrderRequest): Future[ExchangeResponse]
  def cancel(id: String, pair: Instrument): Future[ExchangeResponse]
  def fetchPortfolio: Future[(Map[String, Double], Map[String, Position])]

  // Settings for order size and price rounding
  def baseAssetPrecision(pair: Instrument): Int
  def quoteAssetPrecision(pair: Instrument): Int
  def lotSize(pair: Instrument): Option[Double] = None

  def round(instrument: Instrument)(size: FixedSize[Double]): FixedSize[Double] =
    if (size.security == instrument.security.get)
      size.map(roundBase(instrument))
    else if (size.security == instrument.settledIn.get)
      size.map(roundQuote(instrument))
    else throw new RuntimeException(s"Can't round $size for instrument $instrument")

  def instruments: Future[Set[Instrument]] = Future.successful(Set.empty)

  def genOrderId: String = randomUUID.toString

  protected[flashbot] def _order(req: OrderRequest): Unit = {
    handleResponse(order(req))
  }

  protected[flashbot] def _cancel(id: String, instrument: Instrument): Unit = {
    handleResponse(cancel(id, instrument))
  }

  protected[flashbot] def syntheticCurrentMicros: Option[Long] = None

  private def handleResponse(fut: Future[ExchangeResponse]): Unit = {

    // Simply tick on successful responses, so that the action queue can make progress.
    // On error, use the `error` function which saves the error to internal state (to be
    // collected by the session on the next run) and also calls `tick()`.
    val handler: PartialFunction[Try[ExchangeResponse], Unit] = {
      case Success(RequestOk) =>
        tick(syntheticCurrentMicros.getOrElse(time.currentTimeMicros))
      case Success(RequestFailed(cause: ExchangeError)) =>
        error(cause)
      case Failure(err) =>
        error(InternalError(err))
    }

    if (fut.isCompleted) {
      // Ensure that the tick occurs immediately if the future is instantly complete.
      // This is required for the tick queue to work properly in backtest mode.
      handler(fut.value.get)
    } else {
      // Otherwise, handle asynchronously.
      fut andThen handler
    }
  }

  protected[flashbot] var tick: Long => Unit = (nowMicros: Long) => {
    throw new RuntimeException("The default tick function should never be called")
  }
  protected[flashbot] def setTickFn(fn: Long => Unit): Unit = {
    tick = fn
  }

  private val fills = new ConcurrentLinkedQueue[Fill]()
  def fill(f: Fill): Unit = {
    fills.add(f)
    tick(syntheticCurrentMicros.getOrElse(time.currentTimeMicros))
  }

  private val events = new ConcurrentLinkedQueue[OrderEvent]()
  def event(e: OrderEvent): Unit = {
    events.add(e)
    tick(syntheticCurrentMicros.getOrElse(time.currentTimeMicros))
  }

  private val errors = new ConcurrentLinkedQueue[ExchangeError]()
  private def error(err: ExchangeError): Unit = {
    errors.add(err)
    tick(syntheticCurrentMicros.getOrElse(time.currentTimeMicros))
  }

  private def collectQueue[T](queue: ConcurrentLinkedQueue[T]): Seq[T] = {
    var seq = Seq.empty[T]
    var item = Option(queue.poll())
    while (item.isDefined) {
      seq :+= item.get
      item = Option(queue.poll())
    }
    seq
  }

  /**
    * The internal function that returns user data by the exchange in its current state for
    * the given trading session.
    */
  protected[flashbot] def collect(session: TradingSession,
                                  data: Option[MarketData[_]],
                                  tick: Option[Tick]): (Seq[Fill], Seq[OrderEvent], Seq[ExchangeError]) =
    (collectQueue(fills), collectQueue(events), collectQueue(errors))

  private var jsonParams: Option[Json] = _
  def withParams(json: Option[Json]): Exchange = {
    jsonParams = json
    this
  }

  private def roundQuote(instrument: Instrument)(balance: Double): Double = BigDecimal(balance)
    .setScale(quoteAssetPrecision(instrument), HALF_DOWN).doubleValue()
  private def roundBase(instrument: Instrument)(balance: Double): Double = BigDecimal(balance)
    .setScale(baseAssetPrecision(instrument), HALF_DOWN).doubleValue()

  final def params: ExchangeParams = ExchangeParams(makerFee, takerFee)
}

