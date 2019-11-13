package flashbot.util.timeseries

import flashbot.util.time._
import java.time.{Duration, Instant}

import flashbot.core.Timestamped.HasTime
import org.ta4j.core.{Bar, BaseTimeSeries, TimeSeries}

import scala.collection.AbstractIterator
import scala.collection.mutable.ArrayBuffer

abstract class TimeSeriesLike[A: HasTime](private val it: Iterator[A], val interval: Duration)
    extends AbstractIterator[A] {

  type C
  val elems: C

  protected def insert(item: A): Unit

  private var callbacks = List.empty[A => Unit]

  def onUpdate(fn: A => Unit): this.type = {
    callbacks = fn :: callbacks
    this
  }

  protected[flashbot] def notify(update: A) = {
    callbacks.foreach(_.apply(update))
  }

  private var firstSeenMicros: Long = -1
  private def seqIndexOfMicros(micros: Long): Int = {
    assert(firstSeenMicros > 0)
    (interval.timeStepIndexOfMicros(micros) - interval.timeStepIndexOfMicros(firstSeenMicros)).toInt
  }

  def iloc(i: Int): A = ilocOpt(i).get
  def ilocOpt(i: Int): Option[A]

  def apply(micros: Long): A = iloc(seqIndexOfMicros(micros))
  def get(micros: Long): Option[A] = ilocOpt(seqIndexOfMicros(micros))

  def apply(instant: Instant): A = apply(instant.micros)
  def get(instant: Instant): Option[A] = get(instant.micros)

  override def hasNext = it.hasNext
  override def next() = it.next()
}

trait TimeSeriesFactory[A] {
  def fromTickingIterator(it: Iterator[A], interval: Duration): TimeSeriesLike[A]
}

object TimeSeriesFactory extends TimeSeriesLikeDefaultImplicits {

  class Bars(it: Iterator[Bar], interval: Duration) extends TimeSeriesLike[Bar](it, interval) {
    type C = TimeSeries
    override val elems = buildTimeSeries("TS-Name")
    override protected def insert(item: Bar): Unit = elems.addBar(item)
    override def ilocOpt(i: Int) = Option(elems.getBar(i))
  }

  object Bars extends TimeSeriesFactory[Bar] {
    override def fromTickingIterator(it: Iterator[Bar], interval: Duration) = new Bars(it, interval)
  }
}

trait TimeSeriesLikeDefaultImplicits {
  implicit def genericTimeSeriesLike[A]: TimeSeriesFactory[A] = ???
}


trait TimeSeriesWorkspace {
}

object MyWorkspace extends TimeSeriesWorkspace {
}


