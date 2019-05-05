package flashbot.core

import akka.actor.{ActorRef, Cancellable, Scheduler}

trait EventScheduler {
  protected[flashbot] def setTimeout(micros: Long, event: Tick): Cancellable
  protected[flashbot] def setTimeout(micros: Long, fn: Runnable): Cancellable =
    setTimeout(micros, Callback(fn))

  protected[flashbot] def setInterval(micros: Long, event: Tick): Cancellable
  protected[flashbot] def setInterval(micros: Long, fn: Runnable): Cancellable =
    setTimeout(micros, Callback(fn))

  protected[flashbot] def eventLoop: Option[EventLoop] = None

  protected[flashbot] def emit(event: Tick): Unit

  def fastForward(untilMicros: Long, fn: Tick => Unit): Unit

  def currentMicros: Long = -1
}


class CancellableTimeout(val value: Tick, val interval: Long = -1) extends Tick with Cancellable {
  var cancelled = false

  override def cancel() = {
    if (!isCancelled) {
      cancelled = true
      true
    } else false
  }

  override def isCancelled = cancelled
}

class EventLoopScheduler extends EventScheduler {

  override protected[flashbot] val eventLoop = Some(new EventLoop)

  override protected[flashbot] def setTimeout(micros: Long, event: Tick): Cancellable = {
    val item = new CancellableTimeout(event)
    eventLoop.get.delay(micros, item)
    item
  }

  override protected[flashbot] def setInterval(micros: Long, event: Tick): Cancellable = {
    val item = new CancellableTimeout(event, interval = micros)
    eventLoop.get.delay(micros, item)
    item
  }

  override def fastForward(untilMicros: Long, fn: Tick => Unit): Unit = {
    eventLoop.get.run(untilMicros, t => {
      val ct = t.asInstanceOf[CancellableTimeout]
      if (!ct.isCancelled) {
        fn(ct.value)
        if (ct.interval >= 0) {
          setInterval(ct.interval, ct.value)
        }
      }
    })
  }

  override protected def emit(event: Tick): Unit = this.setTimeout(0, event)

  override def currentMicros: Long = eventLoop.get.currentMicros
}

class RealTimeScheduler(akkaScheduler: Scheduler, tickRef: ActorRef) extends EventScheduler {
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  override protected[flashbot] def setTimeout(micros: Long, event: Tick): Cancellable =
    this.synchronized {
      akkaScheduler.scheduleOnce((micros / 1000).millis, tickRef, event)
    }

  override protected[flashbot] def setInterval(micros: Long, event: Tick): Cancellable =
    this.synchronized {
      akkaScheduler.schedule((micros / 1000).millis, (micros / 1000).millis, tickRef, event)
    }

  override def fastForward(untilMicros: Long, fn: Tick => Unit): Unit = {
    // noop
  }

  override protected def emit(event: Tick): Unit = tickRef ! event
}
