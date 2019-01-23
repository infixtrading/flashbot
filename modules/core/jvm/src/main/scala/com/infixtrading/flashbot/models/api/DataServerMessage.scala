package com.infixtrading.flashbot.models.api

import com.infixtrading.flashbot.models.core.DataPath

sealed trait DataServerMessage

/**
  * The message every data server sends to every other one to register itself across the cluster.
  */
case object RegisterDataServer extends DataServerMessage

sealed trait DataServerException extends Exception

sealed trait DataNotFound extends DataServerException
case class LiveDataNotFound(path: DataPath)
  extends Exception(s"Live data not found at $path.")
    with DataNotFound
case class HistoricalDataNotFound(path: DataPath)
  extends Exception(s"Historical data not found at $path.")
    with DataNotFound
