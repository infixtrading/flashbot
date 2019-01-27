package flashbot.models.api

import java.time.Instant

import flashbot.util.time._
import flashbot.models.core.{DataPath, TimeRange}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
  * DataSelection is a description of a stream of market data.
  *
  * @param path the data path (source, topic, datatype).
  * @param from the start time of data. If none, use the current time.
  * @param to the end time of data. If none, stream indefinitely.
  */
case class DataSelection[T](path: DataPath[T], from: Option[Long] = None, to: Option[Long] = None) {
  def isPolling: Boolean = to.isEmpty
  def timeRange: Try[TimeRange] =
    if (to.isEmpty) Failure(new RuntimeException(s"Cannot create a time range from a polling data selection"))
    else Success(TimeRange(from.getOrElse(0), to.get))
}

object DataSelection {
  def poll[T](path: DataPath[T]): DataSelection[T] = DataSelection(path)
  def pollFrom[T](path: DataPath[T], from: Instant): DataSelection[T] =
    DataSelection(path, Some(from.toEpochMilli * 1000))
  def until[T](path: DataPath[T], to: Instant): DataSelection[T] =
    DataSelection(path, None, Some(to.toEpochMilli * 1000))
}
