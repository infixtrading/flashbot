package flashbot.core

import flashbot.util.time._
import flashbot.models.core.{Candle, DataPath, Ladder, OrderBook}
import io.circe.{Decoder, Encoder}

import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

abstract class DataType[+T] {
  def fmtJson[S >: T]: DeltaFmtJson[S]
  def ordering[S >: T]: Ordering[S] = (x: S, y: S) => 0
  override def toString: String =
    throw new RuntimeException("All DataType subclasses must implement toString")
}

object DataType {

  case object OrderBookType extends DataType[OrderBook] {
    override def fmtJson[S >: OrderBook] =
      OrderBook.orderBookFmt.asInstanceOf[DeltaFmtJson[S]]

    override def toString = "book"
  }
  case class LadderType(depth: Option[Int]) extends DataType[Ladder] {
    override def fmtJson[S >: Ladder] =
      DeltaFmt.defaultFmtJson[Ladder]("ladder").asInstanceOf[DeltaFmtJson[S]]

    override def toString = "ladder" + depth.map(d => "_" + d).getOrElse("")
  }
  case object TradesType extends DataType[Trade] {
    override def fmtJson[S >: Trade] =
      DeltaFmt.defaultFmtJson[Trade]("trades").asInstanceOf[DeltaFmtJson[S]]

    override def toString = "trades"
  }
  case object TickersType extends DataType[Ticker] {
    override def fmtJson[S >: Ticker] = ???
    override def toString = "tickers"
  }
  case class CandlesType(duration: FiniteDuration) extends DataType[Candle] {
    override def fmtJson[S >: Candle] =
      DeltaFmt.defaultFmtJson[Candle]("candles").asInstanceOf[DeltaFmtJson[S]]
    override def toString = "candles_" + printDuration(duration)
  }

  // ??
  case class Series(key: String, timeStep: FiniteDuration) extends DataType[Double] {
    override def fmtJson[S >: Double] = ???
  }

  case object AnyType extends DataType[String] {
    override def fmtJson[_] = throw new RuntimeException("AnyType does not have a delta encoding.")
    override def toString = "*"
  }

  def parse(ty: String): Option[DataType[_]] = ty.split("_").toList match {
    case "book" :: Nil => Some(OrderBookType)
    case "ladder" :: Nil => Some(LadderType(None))
    case "ladder" :: d :: Nil if d matches "[0-9]+" => Some(LadderType(Some(d.toInt)))
    case "candles" :: d :: Nil => Some(CandlesType(parseDuration(d)))
    case "trades" :: Nil => Some(TradesType)
    case "tickers" :: Nil => Some(TickersType)
    case "series" :: key :: d :: Nil => Some(TickersType)
    case "*" :: Nil => Some(AnyType)
    case _ => None
  }

  implicit def apply[T](str: String): DataType[T] = parse(str).get.asInstanceOf[DataType[T]]

  implicit def dataTypeToString[T](dt: DataType[T]): String = dt.toString

  implicit def dataTypeEn[T]: Encoder[DataType[T]] = Encoder.encodeString.contramap(_.toString)
  implicit def dataTypeDe[T]: Decoder[DataType[T]] = Decoder.decodeString.map(DataType.apply)
}
