package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.{Candle, Ladder, OrderBook}
import com.infixtrading.flashbot.util.time.parseDuration
import io.circe.{Decoder, Encoder}

import scala.concurrent.duration.FiniteDuration

// TODO: Does this need to be covariant?
sealed trait DataType[+T] {
  def name: String
  def fmtJson[S >: T]: DeltaFmtJson[S]
//  def fmtJson: DeltaFmtJson[T]
  def ordering[S >: T]: Ordering[S] = (x: S, y: S) => 0

  //  def ordering: Ordering[T] = (x: T, y: T) => 0
  override def toString = name
}

object DataType {

  case object OrderBookType extends DataType[OrderBook] {
    override def name = "book"
    override def fmtJson[S >: OrderBook] = OrderBook.orderBookFmt.asInstanceOf[DeltaFmtJson[S]]
//    override def fmtJson = OrderBook.orderBookFmt
  }
  case class LadderType(depth: Option[Int]) extends DataType[Ladder] {
    override def name = "ladder"
    override def fmtJson[S >: Ladder] = DeltaFmt.defaultFmtJson[Ladder]("ladder").asInstanceOf[DeltaFmtJson[S]]
//    override def fmtJson = DeltaFmt.defaultFmtJson[Ladder]("ladder")
  }
  case object TradesType extends DataType[Trade] {
    override def name = "trades"
    override def fmtJson[S >: Trade] = DeltaFmt.defaultFmtJson[Trade]("trades").asInstanceOf[DeltaFmtJson[S]]
//    override def fmtJson = DeltaFmt.defaultFmtJson[Trade]("trades")
  }
  case object TickersType extends DataType[Ticker] {
    override def name = "tickers"
    override def fmtJson[S >: Ticker] = ???
//    override def fmtJson = ???
  }
  case class CandlesType(duration: FiniteDuration) extends DataType[Candle] {
    override def name = "candles"
    override def fmtJson[S >: Candle] = DeltaFmt.defaultFmtJson[Candle]("candles").asInstanceOf[DeltaFmtJson[S]]
//    override def fmtJson = DeltaFmt.defaultFmtJson[Candle]("candles")
  }

  // ??
  case class Series(key: String, timeStep: FiniteDuration) extends DataType[Double] {
    override def name = "series"
    override def fmtJson[S >: Double] = ???
//    override def fmtJson = ???
  }

  case object AnyType extends DataType[String] {
    override def name = "*"
    override def fmtJson[_] = throw new RuntimeException("AnyType does not have a delta encoding.")
//    override def fmtJson = throw new RuntimeException("AnyType does not have a delta encoding.")
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

