package com.infixtrading.flashbot.models.api

import java.time.Instant

import com.infixtrading.flashbot.models.core.{DataPath, TimeRange}
import com.infixtrading.flashbot.util.time._

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

/**
  * DataSelection is a description of a stream of market data.
  *
  * @param path the data path (source, topic, datatype).
  * @param from the start time of data. If none, use the current time.
  * @param to the end time of data. If none, stream indefinitely.
  */
case class DataSelection(path: DataPath, from: Option[Long] = None, to: Option[Long] = None) {
  def isPolling: Boolean = to.isEmpty
  def timeRange: Try[TimeRange] =
    if (to.isEmpty) Failure(new RuntimeException(s"Cannot create a time range from a polling data selection"))
    else Success(TimeRange(from.getOrElse(0), to.get))
}

object DataSelection {
  def poll(path: DataPath): DataSelection = DataSelection(path)
  def pollFrom(path: DataPath, from: Instant): DataSelection =
    DataSelection(path, Some(from.toEpochMilli * 1000))
  def until(path: DataPath, to: Instant): DataSelection =
    DataSelection(path, None, Some(to.toEpochMilli * 1000))
}
