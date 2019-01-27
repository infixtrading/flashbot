package flashbot.models.api

import flashbot.models.core.DataPath

sealed trait DataServerMessage

/**
  * The message every data server sends to every other one to register itself across the cluster.
  */
case object RegisterDataServer extends DataServerMessage

sealed trait DataServerException extends Exception

sealed trait DataNotFound[T] extends DataServerException {
  def path: DataPath[T]
}
case class LiveDataNotFound[T](path: DataPath[T])
  extends Exception(s"Live data not found at $path.")
    with DataNotFound[T]
case class HistoricalDataNotFound[T](path: DataPath[T])
  extends Exception(s"Historical data not found at $path.")
    with DataNotFound[T]
