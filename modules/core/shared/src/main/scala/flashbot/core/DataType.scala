package flashbot.core

import flashbot.util.time.parseDuration
import flashbot.models.core.{Candle, DataPath, Ladder, OrderBook}
import io.circe.{Decoder, Encoder}

import scala.concurrent.duration.FiniteDuration

abstract class DataType[+T] {
  def name: String
  def fmtJson[S >: T]: DeltaFmtJson[S]
  def ordering[S >: T]: Ordering[S] = (x: S, y: S) => 0
  override def toString = name
}

object DataType {

  case object OrderBookType extends DataType[OrderBook] {
    override def name = "book"
    override def fmtJson[S >: OrderBook] =
      OrderBook.orderBookFmt.asInstanceOf[DeltaFmtJson[S]]
  }
  case class LadderType(depth: Option[Int]) extends DataType[Ladder] {
    override def name = "ladder"
    override def fmtJson[S >: Ladder] =
      DeltaFmt.defaultFmtJson[Ladder]("ladder").asInstanceOf[DeltaFmtJson[S]]
  }
  case object TradesType extends DataType[Trade] {
    override def name = "trades"
    override def fmtJson[S >: Trade] =
      DeltaFmt.defaultFmtJson[Trade]("trades").asInstanceOf[DeltaFmtJson[S]]
  }
  case object TickersType extends DataType[Ticker] {
    override def name = "tickers"
    override def fmtJson[S >: Ticker] = ???
  }
  case class CandlesType(duration: FiniteDuration) extends DataType[Candle] {
    override def name = "candles"
    override def fmtJson[S >: Candle] =
      DeltaFmt.defaultFmtJson[Candle]("candles").asInstanceOf[DeltaFmtJson[S]]
  }

  // ??
  case class Series(key: String, timeStep: FiniteDuration) extends DataType[Double] {
    override def name = "series"
    override def fmtJson[S >: Double] = ???
  }

  case object AnyType extends DataType[String] {
    override def name = "*"
    override def fmtJson[_] = throw new RuntimeException("AnyType does not have a delta encoding.")
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

  def apply[T](str: String): DataType[T] = parse(str).get.asInstanceOf[DataType[T]]

  implicit def dataTypeToString[T](dt: DataType[T]): String = dt.name

  implicit def dataTypeEn[T]: Encoder[DataType[T]] = Encoder.encodeString.contramap(_.name)
  implicit def dataTypeDe[T]: Decoder[DataType[T]] = Decoder.decodeString.map(DataType.apply)
}
