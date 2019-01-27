package flashbot.report

import flashbot.core.DeltaFmt.HasUpdateEvent
import flashbot.report.Report.{ReportError, ReportValue, ValuesMap}
import flashbot.report.ReportDelta._
import flashbot.report.ReportEvent._
import flashbot.util.time._
import flashbot.core.{BalancePoint, DeltaFmt, DeltaFmtJson}
import flashbot.models.core.Candle
import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import io.circe.syntax._

import scala.concurrent.duration._

case class Report(strategy: String,
                  params: Json,
                  barSize: FiniteDuration,
                  trades: Vector[TradeEvent],
                  collections: Map[String, Vector[Json]],
                  timeSeries: Map[String, Vector[Candle]],
                  values: ValuesMap,
                  isComplete: Boolean,
                  error: Option[ReportError],
                  lastUpdate: Option[ReportDelta]) extends HasUpdateEvent[Report, ReportDelta] {

  override protected def withLastUpdate(d: ReportDelta): Report = copy(lastUpdate = Some(d))

  override protected def step(delta: ReportDelta): Report = delta match {
    case TradeAdd(tradeEvent) => copy(trades = trades :+ tradeEvent)
    case CollectionAdd(CollectionEvent(name, item)) => copy(collections = collections +
      (name -> (collections.getOrElse(name, Vector.empty[Json]) :+ item)))
    case event: ValueEvent => copy(values = event match {
      case PutValueEvent(key, fmtName, valueJson) =>
        val fmt = DeltaFmt.formats(fmtName)
        setVal(fmt, key, valueJson, values)

      case UpdateValueEvent(key, deltaJson) =>
        val fmt = DeltaFmt.formats(values(key).fmtName)
        updateVal(fmt, key, deltaJson, values)

      case RemoveValueEvent(key) =>
        values - key
    })

    case RawEvent(SessionComplete(errOpt)) =>
      copy(isComplete = true, error = errOpt)

    case RawEvent(event: CandleEvent) => event match {
      case CandleAdd(series, candle) =>
        copy(timeSeries = timeSeries + (series ->
          (timeSeries.getOrElse(series, Vector.empty) :+ candle)))
      case CandleUpdate(series, candle) =>
        copy(timeSeries = timeSeries + (series ->
          timeSeries(series).updated(timeSeries(series).length - 1, candle)))
    }
  }

  private def setVal[T](fmt: DeltaFmtJson[T], key: String, value: Json,
                        values: ValuesMap): ValuesMap = {
    val tv = value.as[T](fmt.modelDe).right.get
    values + (key -> ReportValue(fmt.fmtName, tv))
  }

  private def updateVal[T](fmt: DeltaFmtJson[T], key: String, delta: Json,
                           values: ValuesMap): ValuesMap = {
    val dv = delta.as[fmt.D](fmt.deltaDe).right.get
    val v = values(key).value.asInstanceOf[T]
    val newVal = fmt.update(v, dv)
    values + (key -> ReportValue(fmt.fmtName, newVal))
  }

  def genDeltas(event: ReportEvent): Seq[ReportDelta] = event match {
    case tradeEvent: TradeEvent =>
      TradeAdd(tradeEvent) :: Nil

    case BalanceEvent(acc, balance, micros) =>
      CollectionAdd(CollectionEvent(acc.toString, BalancePoint(balance, micros).asJson)) :: Nil

    case collectionEvent: CollectionEvent =>
      CollectionAdd(collectionEvent) :: Nil

    case e: ReportValueEvent => List(e.event)

    case other => Seq(RawEvent(other))
  }


  /**
    * Generates either a CandleSave followed by a CandleAdd, or a CandleUpdate by itself.
    */
//  private def genTimeSeriesDelta[T <: Timestamped](series: String,
//                                                   event: T,
//                                                   valueFn: T => Double): ReportDelta = {
//    val value = valueFn(event)
//    val newBarMicros = (event.micros / barSize.toMicros) * barSize.toMicros
//    val currentTS: Seq[Candle] = timeSeries.getOrElse(series, Vector.empty)
//    if (currentTS.lastOption.exists(_.micros == newBarMicros))
//      CandleUpdate(series, currentTS.last.add(value))
//    else
//      CandleAdd(series, Candle(newBarMicros, value, value, value, value))
//  }

}

object Report {
  type ValuesMap = Map[String, ReportValue[_]]

  case class ReportError(name: String, message: String, trace: Seq[String],
                         cause: Option[ReportError])
  object ReportError {
    def apply(err: Throwable): ReportError =
      ReportError(err.getClass.getName, err.getMessage,
        err.getStackTrace.toSeq.map(_.toString), Option(err.getCause).map(ReportError(_)))

    implicit def en: Encoder[ReportError] = deriveEncoder[ReportError]
    implicit def de: Decoder[ReportError] = deriveDecoder[ReportError]
  }

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

  implicit val reportEn: Encoder[Report] = Encoder.forProduct10(
    "strategy", "params", "barSize", "trades", "collections", "timeSeries", "values",
        "isComplete", "error", "lastUpdate")(r =>
      (r.strategy, r.params, r.barSize, r.trades, r.collections, r.timeSeries, r.values,
        r.isComplete, r.error, r.lastUpdate))
  implicit val reportDe: Decoder[Report] = Decoder.forProduct10(
    "strategy", "params", "barSize", "trades", "collections",
    "timeSeries", "values", "isComplete", "error", "lastUpdate")(Report.apply)

  def empty(strategyName: String,
            params: Json,
            barSize: Option[FiniteDuration] = None): Report = Report(
    strategyName,
    params,
    barSize.getOrElse(1 hour),
    Vector(),
    Map.empty,
    Map.empty,
    Map.empty,
    isComplete = false,
    None,
    None
  )

  implicit val reportFmt: DeltaFmtJson[Report] =
    DeltaFmt.updateEventFmtJson[Report, ReportDelta]("report")
}

