package flashbot.core

import java.util.Comparator

import flashbot.models.api.TradingSessionEvent
import it.unimi.dsi.fastutil.longs.{Long2ObjectLinkedOpenHashMap, LongHeapPriorityQueue}
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap
import it.unimi.dsi.fastutil.objects.ObjectHeapPriorityQueue
import spire.syntax.cfor._
//import org.agrona.collections.Long2ObjectCache

class EventBuffer(initialCapacity: Int) {
  lazy val buffer = debox.Buffer.fill[TradingSessionEvent](initialCapacity)(null)
  var size: Int = 0

  def +=(event: TradingSessionEvent) = {
    if (size == buffer.len) {
      buffer += event
    } else if (size < buffer.len) {
      buffer(size) = event
    } else {
      throw new RuntimeException("EventBuffer size should never be larger than underlying.")
    }
    size += 1
  }

  def consume(fn: TradingSessionEvent => Unit): Unit = {
    cfor(0)(_ < size, _ + 1) { i =>
      fn(buffer(i))
      buffer(i) = null
    }
    size = 0
  }
}

object EventBuffer {
  val comparator: Comparator[EventBuffer] = Comparator.comparingLong[EventBuffer](buf => buf.buffer(0).micros)
}

/**
  * Simulates the time sorted ticks of events behavior of a trading session in live mode.
  */
class EventLoop {

  var bufferPool: List[EventBuffer] = List.fill(100)(new EventBuffer(100))
  val heap: ObjectHeapPriorityQueue[EventBuffer] = new ObjectHeapPriorityQueue(EventBuffer.comparator)
  var collector = new EventBuffer(1000)
  var eventstream = new EventBuffer(1000)

  // Event loop for backtests. untilMicros is inclusive.
  def run(untilMicros: Long, fn: TradingSessionEvent => Unit): Unit = {
    loadNextBuffer(untilMicros)
    while (eventstream.size > 0) {
      consumeBuffer(eventstream, fn)
      loadNextBuffer(untilMicros)
    }
  }

  // Prepare the next buffer for evaluation.
  private def loadNextBuffer(untilMicros: Long): Unit = {
    if (eventstream.size == 0) {
      // If collector is non-empty, swap it with the eventstream.
      if (collector.size > 0) {
        val tmp = eventstream
        eventstream = collector
        collector = tmp
      } else if (!heap.isEmpty && heap.first().buffer(0).micros <= untilMicros) {
        eventstream = heap.dequeue()
      }
    }
  }

  // Try to get from cache. If non exists, create it.
  private def acquireBuffer(): EventBuffer = {

  }

  // EventBuffers are always placed back in the pool and they are never shrunk.
  private def releaseBuffer(buf: EventBuffer) = {
    bufferPool = buf :: bufferPool
  }

  def insert(micros: Long, event: TradingSessionEvent): Unit = {
    // If is immediate, add to buffer.
    if (micros == 0) {
      immediate += event
    }
  }

  private def consumeBuffer(buf: EventBuffer, fn: TradingSessionEvent => Unit) = {
    buf.consume(fn)
    releaseBuffer(buf)
  }
}

object EventLoop {
  def empty: EventLoop = new EventLoop
}
