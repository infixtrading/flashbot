package flashbot.core

import flashbot.models._
import flashbot.models.OrderCommand.{PostLimitOrder, PostMarketOrder, PostOrderCommand}
import flashbot.models.Order.{Buy, Sell, Side}
import flashbot.util.NumberUtils
import java.util.UUID.randomUUID

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

abstract class OrderRef {

  implicit protected[flashbot] var ctx: TradingSession = _
  implicit protected[flashbot] var parent: OrderRef = _

  protected[flashbot] var _tag: String = ""
  final def tag: String = _tag

  lazy val id: String = randomUUID.toString

  val children = new OrderIndex

  // Will be set by the session
  protected[flashbot] var seqNr: Long = -1

  final private lazy val tagWithSeqNr: String =
    if (!tag.isEmpty && seqNr == 0) tag
    else if (!tag.isEmpty) s"$tag-$seqNr"
    else seqNr.toString

  final lazy val key: String =
    if (parent == null) tagWithSeqNr
    else s"${parent.key}/$tagWithSeqNr"

  def handleSubmit(): Unit
  def handleCancel(): Unit

  private var dirty: Boolean = false
  lazy val receivedCallbacks: debox.Buffer[OrderReceived => Unit] = debox.Buffer.empty
  lazy val openCallbacks: debox.Buffer[OrderOpen => Unit] = debox.Buffer.empty
  lazy val doneCallbacks: debox.Buffer[OrderDone => Unit] = debox.Buffer.empty
  lazy val matchCallbacks: debox.Buffer[OrderMatch => Unit] = debox.Buffer.empty
  lazy val changeCallbacks: debox.Buffer[OrderChange => Unit] = debox.Buffer.empty
  lazy val errorCallbacks: debox.Buffer[OrderError => Unit] = debox.Buffer.empty

  final def onReceived(callback: OrderReceived => Unit): OrderRef = {
    bindAndRegister(receivedCallbacks, callback)
    this
  }

  final def onOpen(callback: OrderOpen => Unit): OrderRef = {
    bindAndRegister(openCallbacks, callback)
    this
  }

  final def onDone(callback: OrderDone => Unit): OrderRef = {
    bindAndRegister(doneCallbacks, callback)
    this
  }

  final def onMatch(callback: OrderMatch => Unit): OrderRef = {
    bindAndRegister(matchCallbacks, callback)
    this
  }

  final def onChange(callback: OrderChange => Unit): OrderRef = {
    bindAndRegister(changeCallbacks, callback)
    this
  }

  final def onError(callback: OrderError => Unit): OrderRef = {
    bindAndRegister(errorCallbacks, callback)
    this
  }

  private def bindAndRegister[T <: OrderEvent](buf: debox.Buffer[T => Unit], cb: T => Unit): Unit = {
    dirty = true
    buf += bind(cb)
  }

  private def bind[T](fn: T => Unit): T => Unit = (event: T) => {
    val proto = ctx.scope
    ctx.scope = this
    fn(event)
    ctx.scope = proto
  }

  protected def handleEvent(event: OrderEvent): Unit = {
    if (!dirty)
      return

    event match {
      case ev: OrderReceived =>
        receivedCallbacks.foreach(_.apply(ev))
      case ev: OrderOpen =>
        openCallbacks.foreach(_.apply(ev))
      case ev: OrderDone =>
        doneCallbacks.foreach(_.apply(ev))
      case ev: OrderMatch =>
        matchCallbacks.foreach(_.apply(ev))
      case ev: OrderChange =>
        changeCallbacks.foreach(_.apply(ev))
      case ev: OrderError =>
        errorCallbacks.foreach(_.apply(ev))
    }
  }

  protected[flashbot] def _handleEvent(event: OrderEvent): Unit = handleEvent(event)
}

class PersistentQuote(val market: Market,
                      val side: Side,
                      var size: Double,
                      var price: Double) extends OrderRef {

  private val qtys = debox.Map.empty[String, Double]

  private def currentTotal = {
    var total = 0d
    qtys.foreachValue(total += _)
    total
  }

  override def handleSubmit() = {
    submitRemainder()
  }

  private def submitRemainder(): OrderRef = {
    val remainder: Double = NumberUtils.round8(size - currentTotal)
    ctx.submit(new LimitOrder(market, side, remainder, price))
      .onOpen { ev =>
        qtys.update(ev.orderId, ev.size)
      }
      .onChange { ev =>
        qtys.update(ev.orderId, ev.newSize)
        submitRemainder()
      }
      .onMatch { ev =>
        qtys.update(ev.orderId, NumberUtils.round8(qtys(ev.orderId) - ev.size))
        submitRemainder()
      }
      .onDone { ev =>
        qtys.remove(ev.orderId)
        submitRemainder()
      }
  }

  override def handleCancel() = {
    qtys.foreachKey(ctx.cancel)
  }

  def updatePrice(newPrice: Double): OrderRef = {
    price = newPrice
    submitRemainder()
  }

  def updateSize(newSize: Double): OrderRef = {
    size = newSize
    submitRemainder()
  }
}

sealed abstract class BuiltInOrder extends OrderRef {

  def market: Market
  def side: Side
  def size: Double

  final lazy val exchange = ctx.exchanges(market.exchange)
  override final lazy val id: String = exchange.genOrderId

  private var exchangeId: String = _

  override def handleSubmit(): Unit = {
    // Submit the order directly to the exchange.
    exchange.order(submitCmd) onComplete {

      case Success(RequestSuccess) => // Do nothing on success

      case Success(RequestError(cause: ExchangeError)) => cause match {
        case err @ OrderRejectedError(request, reason) =>
          ctx.emit(OrderRejected(id, market.symbol, reason, err))
        case err: ExchangeError =>
          ctx.emit(OrderException(id, market.symbol, err))
      }

      // This is an unexpected network error.
      // Most likely the internet or exchange is down.
      case Failure(err) =>
        ctx.emit(OrderException(id, market.symbol, err))
    }
  }

  override def handleCancel(): Unit = {
    exchange.cancel(exchangeId, ctx.instruments(market)) onComplete {
      case Success(RequestSuccess) => // Do nothing on success

      case Success(RequestError(cause: ExchangeError)) =>
        ctx.emit(CancelError(id, market.symbol, cause))

      case Failure(err) =>
        ctx.emit(OrderException(id, market.symbol, err))
    }
  }

  def submitCmd: PostOrderRequest
}

class LimitOrder(val market: Market,
                 val side: Side,
                 val size: Double,
                 val price: Double,
                 val postOnly: Boolean = false) extends BuiltInOrder {
  override def submitCmd = LimitOrderRequest(id, side, ctx.instruments(market), size, price, postOnly)
}

class MarketOrder(val market: Market,
                  val side: Side,
                  val size: Double) extends BuiltInOrder {

  def this(market: Market, size: Double) {
    this(market, if (size > 0) Buy else Sell, size)
  }

  override def submitCmd = MarketOrderRequest(id, side, ctx.instruments(market), size)

  override def handleCancel(): Unit =
    throw new UnsupportedOperationException("Market orders do not support cancellations.")
}
