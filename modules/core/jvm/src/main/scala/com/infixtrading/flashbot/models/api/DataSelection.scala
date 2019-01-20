package com.infixtrading.flashbot.models.api

import com.infixtrading.flashbot.models.core.{DataPath, TimeRange}

/**
  * DataSelection is a description of a stream of market data.
  *
  * @param path the data path (source, topic, datatype).
  * @param from the start time of data. If none, use the current time.
  * @param to the end time of data. If none, stream indefinitely.
  */
case class DataSelection(path: DataPath, from: Option[Long] = None, to: Option[Long] = None) {
  def isPolling: Boolean = to.isEmpty
  def timeRange: Option[TimeRange] =
    for {
      f <- from
      t <- to
    } yield TimeRange(f, t)
}
