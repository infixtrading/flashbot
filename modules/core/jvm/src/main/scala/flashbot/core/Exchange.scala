package flashbot.core

import java.util.UUID.randomUUID
import java.util.concurrent.ConcurrentLinkedQueue

import flashbot.models.core.FixedSize
import flashbot.models.core.Order.Fill
import flashbot.models.core._
import io.circe.Json

import scala.concurrent.{ExecutionContext, Future}
import scala.math.BigDecimal.RoundingMode.HALF_DOWN
import scala.util.{Failure, Success}

abstract class Exchange {

  implicit val ec: ExecutionContext

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
    else if (size.security == instrument.settledIn)
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

  private def handleResponse(fut: Future[ExchangeResponse]): Unit = {
    fut onComplete {
      case Success(RequestOk) => // Ignore successful responses
      case Success(RequestFailed(cause: ExchangeError)) =>
        error(cause)
      case Failure(err) =>
        error(InternalError(err))
    }
  }

  private var tick: () => Unit = () => {
    throw new RuntimeException("The default tick function should never be called")
  }
  protected[flashbot] def setTickFn(fn: () => Unit): Unit = {
    tick = fn
  }

  private val fills = new ConcurrentLinkedQueue[Fill]()
  def fill(f: Fill): Unit = {
    fills.add(f)
    tick()
  }

  private val events = new ConcurrentLinkedQueue[OrderEvent]()
  def event(e: OrderEvent): Unit = {
    events.add(e)
    tick()
  }

  private val errors = new ConcurrentLinkedQueue[ExchangeError]()
  private def error(err: ExchangeError): Unit = {
    errors.add(err)
    tick()
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
                                  data: Option[MarketData[_]]): (Seq[Fill], Seq[OrderEvent], Seq[ExchangeError]) =
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
}

