package flashbot.util.timeseries

import org.ta4j.core.{Bar, BaseTimeSeries, TimeSeries}

import scala.collection.mutable.ArrayBuffer
import flashbot.util.timeseries._
import flashbot.util.timeseries.Implicits._

/**
 * Simple type class to put things like builders for time series collections.
 */
trait TimeSeriesLike[A] {
  type Repr <: Iterable[A]
  def fromIterable(it: Iterable[A]): Repr
  def empty(name: String): Repr
}

object TimeSeriesLike extends DefaultImplicits {

  implicit object Ta4jCollectionType extends TimeSeriesLike[Bar] {
    case class Repr(series: TimeSeries) extends Iterable[Bar] {
      override def iterator = series.iterator
    }
    override def fromIterable(it: Iterable[Bar]) = {
      val ts = empty("Time Series")
      it.foreach(bar =>
        ts.series.addBar(bar)
//        ts.series.put(bar.candle)
      )
      ts
    }
    override def empty(name: String) = Repr(buildTimeSeries(name))
  }
}

trait DefaultImplicits {
  implicit def genericTimeSeriesLike[A]: TimeSeriesLike[A] = new TimeSeriesLike[A] {
    override type Repr = ArrayBuffer[A]
    override def fromIterable(it: Iterable[A]) = empty("") ++ it
    override def empty(name: String) = new ArrayBuffer[A]()
  }
}

