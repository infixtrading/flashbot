package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.models.core.{Candle, Ladder, OrderBook}
import com.infixtrading.flashbot.util.time.parseDuration

import scala.concurrent.duration.FiniteDuration

sealed trait DataType[T] {
  private val defaultOrdering = new Ordering[T] {
    override def compare(x: T, y: T) = 0
  }

  def name: String
  def fmtJson: DeltaFmtJson[T]
  def ordering: Ordering[T] = defaultOrdering
}

object DataType {

  case object OrderBookType extends DataType[OrderBook] {
    override def name = "book"
    override def fmtJson = OrderBook.orderBookFmt
  }
  case class LadderType(depth: Option[Int]) extends DataType[Ladder] {
    override def name = "ladder"
    override def fmtJson = DeltaFmt.defaultFmtJson[Ladder]("ladder")
  }
  case object TradesType extends DataType[Trade] {
    override def name = "trades"
    override def fmtJson = DeltaFmt.defaultFmtJson[Trade]("trades")
  }
  case object TickersType extends DataType[Ticker] {
    override def name = "tickers"
    override def fmtJson = ???
  }
  case class CandlesType(duration: FiniteDuration) extends DataType[Candle] {
    override def name = "candles"
    override def fmtJson = DeltaFmt.defaultFmtJson[Candle]("candles")
  }

  case class Series(key: String, timeStep: FiniteDuration) extends DataType[Double] {
    override def name = "series"
    override def fmtJson = ???
  }

  def parse(ty: String): Option[DataType[_]] = ty.split("_").toList match {
    case "book" :: Nil => Some(OrderBookType)
    case "ladder" :: Nil => Some(LadderType(None))
    case "ladder" :: d :: Nil if d matches "[0-9]+" => Some(LadderType(Some(d.toInt)))
    case "candles" :: d :: Nil => Some(CandlesType(parseDuration(d)))
    case "trades" :: Nil => Some(TradesType)
    case "tickers" :: Nil => Some(TickersType)
    case "series" :: key :: d :: Nil => Some(TickersType)
    case _ => None
  }

  def apply[T](str: String): DataType[T] = parse(str).get.asInstanceOf[DataType[T]]
}

