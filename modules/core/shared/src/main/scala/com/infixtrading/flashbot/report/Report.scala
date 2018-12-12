package com.infixtrading.flashbot.report

import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.models.core.Candle
import com.infixtrading.flashbot.report.Report.{ReportValue, ValuesMap}
import com.infixtrading.flashbot.report.ReportDelta._
import com.infixtrading.flashbot.report.ReportEvent._
import com.infixtrading.flashbot.util.time._
import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import io.circe.syntax._

import scala.concurrent.duration._

case class Report(strategy: String,
                  params: Json,
                  barSize: Duration,
                  trades: Vector[TradeEvent],
                  collections: Map[String, Vector[Json]],
                  timeSeries: Map[String, Vector[Candle]],
                  values: ValuesMap) {

  def update(delta: ReportDelta): Report = delta match {
    case TradeAdd(tradeEvent) => copy(trades = trades :+ tradeEvent)
    case CollectionAdd(CollectionEvent(name, item)) => copy(collections = collections +
      (name -> (collections.getOrElse(name, Vector.empty[Json]) :+ item)))
    case event: CandleEvent => event match {
      case CandleAdd(series, candle) =>
        copy(timeSeries = timeSeries + (series ->
          (timeSeries.getOrElse(series, Vector.empty) :+ candle)))
      case CandleUpdate(series, candle) =>
        copy(timeSeries = timeSeries + (series ->
          timeSeries(series).updated(timeSeries(series).length - 1, candle)))
    }
    case event: ValueEvent => copy(values = event match {
      case PutValueEvent(key, fmtName, anyValue) =>
        val fmt = DeltaFmt.formats(fmtName)
        setVal(fmt, key, anyValue, values)

      case UpdateValueEvent(key, anyDelta) =>
        val fmt = DeltaFmt.formats(values(key).fmtName)
        updateVal(fmt, key, anyDelta, values)

      case RemoveValueEvent(key) =>
        values - key
    })
  }

  private def setVal[T](fmt: DeltaFmt[T], key: String, value: Any,
                        values: ValuesMap): ValuesMap = {
    val tv = value.asInstanceOf[T]
    values + (key -> ReportValue(fmt.fmtName, tv))
  }

  private def updateVal[T](fmt: DeltaFmt[T], key: String, delta: Any,
                           values: ValuesMap): ValuesMap = {
    val dv = delta.asInstanceOf[fmt.D]
    val v = values(key).asInstanceOf[T]
    val newVal = fmt.update(v, dv)
    values + (key -> ReportValue(fmt.fmtName, newVal))
  }

  def genDeltas(event: ReportEvent): Seq[ReportDelta] = event match {
    case tradeEvent: TradeEvent =>
      TradeAdd(tradeEvent) :: Nil

    case collectionEvent: CollectionEvent =>
      CollectionAdd(collectionEvent) :: Nil

    case e: PriceEvent =>
      genTimeSeriesDelta[PriceEvent](
        List("price", e.market.exchange, e.market.symbol).mkString("."), e, _.price) :: Nil

    case e: BalanceEvent =>
      genTimeSeriesDelta[BalanceEvent](
        List("balance", e.account.exchange, e.account.security).mkString("."), e, _.balance) :: Nil

    case e: PositionEvent =>
      genTimeSeriesDelta[PositionEvent](
        List("position", e.market.exchange, e.market.symbol).mkString("."), e, _.position.size) :: Nil

    case e: TimeSeriesEvent =>
      genTimeSeriesDelta[TimeSeriesEvent](e.key, e, _.value) :: Nil

    case e: TimeSeriesCandle =>
      CandleAdd(e.key, e.candle) :: Nil

    case e: ReportValueEvent => List(e.event)
  }


  /**
    * Generates either a CandleSave followed by a CandleAdd, or a CandleUpdate by itself.
    */
  private def genTimeSeriesDelta[T <: Timestamped](series: String,
                                                   event: T,
                                                   valueFn: T => Double): ReportDelta = {
    val value = valueFn(event)
    val newBarMicros = (event.micros / barSize.toMicros) * barSize.toMicros
    val currentTS: Seq[Candle] = timeSeries.getOrElse(series, Vector.empty)
    if (currentTS.lastOption.exists(_.micros == newBarMicros))
      CandleUpdate(series, currentTS.last.add(value))
    else
      CandleAdd(series, Candle(newBarMicros, value, value, value, value))
  }

}

object Report {
  type ValuesMap = Map[String, ReportValue[_]]

  /**
    * ReportValues are stored on disk with default Java serialization.
    * They may also be transferred over the network via JSON.
    */
  case class ReportValue[T](fmtName: String, value: T) {
    def map[K](fn: T => K): ReportValue[K] = copy[K](value = fn(value))
  }
  val rvJsonDecoder: Decoder[ReportValue[Json]] = deriveDecoder[ReportValue[Json]]
  val rvJsonEncoder: Encoder[ReportValue[Json]] = deriveEncoder[ReportValue[Json]]

  implicit val vMapEn: Encoder[ValuesMap] = new Encoder[ValuesMap] {
    override def apply(a: ValuesMap) = {
      val jsonMap: Map[String, Json] = a.foldLeft(Map.empty[String, Json])((memo, kv) => {
        memo + (kv._1 -> rvEncode(kv._2))
      })
      jsonMap.asJson
    }

    def rvEncode[T](rv: ReportValue[T]): Json = {
      DeltaFmt.formats(rv.fmtName) match {
        case fmt: DeltaFmt[T] =>
          rvJsonEncoder(rv.map(value => fmt.modelEn(value)))
      }
    }
  }


  implicit val vMapDe: Decoder[ValuesMap] = new Decoder[ValuesMap] {
    override def apply(c: HCursor) = {
      c.as[Map[String, Json]].right.map(_.mapValues(reportVal))
    }

    def reportVal(obj: Json): ReportValue[_] =
      rvJsonDecoder.decodeJson(obj) match {
        case Right(rv) =>
          val fmt = DeltaFmt.formats(rv.fmtName)
          rv.map(jsonVal => fmt.modelDe.decodeJson(jsonVal).right.get)
      }
  }

  implicit val reportEn: Encoder[Report] = Encoder.forProduct7(
    "strategy", "params", "barSize", "trades", "collections", "timeSeries", "values")(r =>
      (r.strategy, r.params, r.barSize, r.trades, r.collections, r.timeSeries, r.values))
  implicit val reportDe: Decoder[Report] = Decoder.forProduct7(
    "strategy", "params", "barSize", "trades", "collections",
    "timeSeries", "values")(Report.apply)

  def empty(strategyName: String,
            params: Json,
            barSize: Option[Duration] = None): Report = Report(
    strategyName,
    params,
    barSize.getOrElse(1 minute),
    Vector(),
    Map.empty,
    Map.empty,
    Map.empty
  )
}

