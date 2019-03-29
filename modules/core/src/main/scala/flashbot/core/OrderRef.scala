package flashbot.core

import flashbot.models.api.TradingSessionEvent
import flashbot.models.api.OrderCommand.{PostLimitOrder, PostMarketOrder, PostOrderCommand}
import flashbot.models.core.Market
import flashbot.models.core.Order.Side

abstract class OrderRef {

  implicit protected[flashbot] var ctx: TradingSession = _

  def key: String = ""

  def submit(): Unit
  def cancel(): Unit

  private var dirty: Boolean = false
  lazy val receivedCallbacks: debox.Buffer[OrderReceived => Unit] = debox.Buffer.empty
  lazy val openCallbacks: debox.Buffer[OrderOpen => Unit] = debox.Buffer.empty
  lazy val doneCallbacks: debox.Buffer[OrderDone => Unit] = debox.Buffer.empty
  lazy val matchCallbacks: debox.Buffer[OrderMatch => Unit] = debox.Buffer.empty
  lazy val changeCallbacks: debox.Buffer[OrderChange => Unit] = debox.Buffer.empty

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

  final protected def genId(): String = ???

  private def bindAndRegister[T <: TradingSessionEvent](buf: debox.Buffer[T => Unit], cb: T => Unit) = {
    dirty = true
    buf += bind(cb)
  }

  private def bind[T](fn: T => Unit): T => Unit = (event: T) => {
    val proto = ctx.scope
    ctx.scope = this
    fn(event)
    ctx.scope = proto
  }

  protected def onEvent(event: TradingSessionEvent): Unit = {
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
    }
  }
}

abstract class ManagedOrder[P] extends OrderRef

sealed abstract class BuiltInOrder extends OrderRef {
  def market: Market
  def side: Side
  def size: Double

  final lazy val exchange = ctx.exchanges(market.exchange)
  final lazy val id: String = exchange.genOrderId

  override def submit() = {
    exchange._order(id, submitCmd)
  }

  override def cancel() = {
    exchange._cancel(id, ctx.instruments(market))
  }

  def submitCmd: PostOrderCommand
}

class LimitOrder(val market: Market,
                 val side: Side,
                 val size: Double,
                 val price: Double,
                 val postOnly: Boolean = false) extends BuiltInOrder {
  override def submitCmd = PostLimitOrder(id, side, size, price, postOnly)
}

class MarketOrder(val market: Market,
                  val side: Side,
                  val size: Double) extends BuiltInOrder {
  override def cancel() =
    throw new UnsupportedOperationException("Market orders do not support cancellations.")

  override def submitCmd = PostMarketOrder(id, side, size)
}
