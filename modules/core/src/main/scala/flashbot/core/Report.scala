package flashbot.core

import flashbot.core.DeltaFmt.HasUpdateEvent
import flashbot.core.Report._
import flashbot.core.ReportDelta._
import flashbot.core.ReportEvent._
import flashbot.models.{Candle, Portfolio}
import flashbot.util.time._
import flashbot.util.json.CommonEncoders._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import it.unimi.dsi.fastutil.objects.{Object2ObjectOpenHashMap, ObjectArrayFIFOQueue}

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

class Report(val strategy: String,
             val params: Json,
             val barSize: FiniteDuration,
             val portfolio: Portfolio,
             val trades: debox.Buffer[TradeEvent],
             val collections: debox.Map[String, debox.Buffer[Json]],
             val timeSeries: debox.Map[String, CandleFrame],
             val values: debox.Map[String, ReportValue[Any]],
             var isComplete: Boolean,
             var error: Option[ReportError],
             val lastUpdate: MutableOpt[ReportDelta]) extends HasUpdateEvent[Report, ReportDelta] {

  override protected def _step(delta: ReportDelta): Report = delta match {
    case TradeAdd(tradeEvent) =>
      trades += tradeEvent
      this

    case CollectionAdd(CollectionEvent(name, item)) =>
      var coll = collections.get(name)
      if (coll.isEmpty) {
        val buf = debox.Buffer[Json](item)
        collections(name) = buf
      } else {
        coll.get += item
      }
      this

    case event: ValueEvent => event match {
      case PutValueEvent(key, fmtName, valueJson) =>
        val fmt = DeltaFmt.formats(fmtName)
        setVal(fmt, key, valueJson)
        this

      case UpdateValueEvent(key, deltaJson) =>
        val fmt = DeltaFmt.formats(values(key).fmtName)
        updateVal(fmt, key, deltaJson)
        this

      case RemoveValueEvent(key) =>
        values.remove(key)
        this
    }

    case RawEvent(SessionComplete(errOpt)) =>
      isComplete = true
      error = errOpt
      this

    case RawEvent(event: CandleEvent) => event match {
      case CandleAdd(series, candle) =>
        var frame = timeSeries.get(series)
        if (frame.isEmpty) {
          val f = new CandleFrame
          f.insert(candle)
          timeSeries(series) = f
        } else {
          frame.get.insert(candle)
        }
        this

      case CandleUpdate(series, candle) =>
        var frame = timeSeries.get(series)
        if (frame.isEmpty) {
          val f = new CandleFrame
          f.replaceLast(candle)
          timeSeries(series) = f
        } else {
          frame.get.replaceLast(candle)
        }
        this
    }

    case RawEvent(portfolioDelta: PortfolioDelta) =>
      portfolio.update(portfolioDelta)
      this
  }

  private def setVal[T](fmt: DeltaFmtJson[T], key: String, value: Json): Unit = {
    val tv = value.as[T](fmt.modelDe).right.get
    values(key) = ReportValue(fmt.fmtName, tv)
  }

  private def updateVal[T](fmt: DeltaFmtJson[T], key: String, delta: Json): Unit = {
    val dv = delta.as[fmt.D](fmt.deltaDe).right.get
    val rv = getval[T](key)
    rv.value = fmt.update(rv.value, dv)
  }

  def genDeltas(event: ReportEvent): Seq[ReportDelta] = event match {
    case tradeEvent: TradeEvent =>
      TradeAdd(tradeEvent) :: Nil

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


  def getval[T](key: String): ReportValue[T] = values.get(key).asInstanceOf[ReportValue[T]]

}

object Report {

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
  class ReportValue[T](val fmtName: String, var value: T) {
    def toJson: Json = DeltaFmt.formats[T](fmtName).modelEn(value)
//    def map(fn: T => T): ReportValue[T] = {
//      value = fn(value)
//      this
//    }
  }

  object ReportValue {
    def apply[T](fmtName: String, value: T): ReportValue[T] =
      new ReportValue[T](fmtName, value)
  }

  val rvJsonDecoder: Decoder[ReportValue[Json]] =
    Decoder.forProduct2[ReportValue[Json], String, Json](
      "fmtName", "value")(new ReportValue[Json](_, _))

  val rvJsonEncoder: Encoder[ReportValue[Json]] =
    Encoder.forProduct2("fmtName", "value")(rv => (rv.fmtName, rv.value))

  implicit val rvAnyDecoder: Decoder[ReportValue[Any]] =
    rvJsonDecoder.map { rv =>
      val fmt = DeltaFmt.formats(rv.fmtName)
      ReportValue(rv.fmtName, fmt.modelDe.decodeJson(rv.value).right.get)
    }

  implicit val rvAnyEncoder: Encoder[ReportValue[Any]] =
    rvJsonEncoder.contramap(rv => ReportValue(rv.fmtName, rv.toJson))

//  implicit val vMapEn: Encoder[debox.Map[String, ReportValue[Any]]] =
//    implicitly[Encoder[debox.Map[String, ReportValue[Json]]]].contramap(_.mapValues(rvEncode))

//  def rvEncode[T](rv: ReportValue[Any]): Json =
//    rvJsonEncoder(ReportValue(rv.fmtName,
//      DeltaFmt.formats[T](rv.fmtName).modelEn(rv.value.asInstanceOf[T])))

//  implicit val vMapEn: Encoder[debox.Map[String, Any]] = new Encoder[debox.Map[String, Any]] {
//    override def apply(a: debox.Map[String, Any]): Json = {
//      var jsonMap: JsonObject = JsonObject()
//      a.foreachKey { key =>
//        jsonMap = jsonMap.add(key, rvEncode(a(key)))
//      }
//      jsonMap.asJson
//    }

//  }

//  implicit val vMapDe: Decoder[debox.Map[String, ReportValue[Any]]] = Decoder.decodeJsonObject.map { o =>
//    val vmap = debox.Map[String, ReportValue[Any]]()
//    for (key <- o.keys) {
//      vmap(key) = _reportVal(o(key).get)
//    }
//    vmap
//  }

//  private def _reportVal(obj: Json): ReportValue[Any] =
//    rvJsonDecoder.decodeJson(obj) match {
//      case Right(rv) =>
//        val fmt = DeltaFmt.formats(rv.fmtName)
//        ReportValue(rv.fmtName, fmt.modelDe.decodeJson(rv.value).right.get)
//    }

  implicit val reportEn: Encoder[Report] = Encoder.forProduct11(
    "strategy", "params", "barSize", "portfolio", "trades", "collections",
    "timeSeries", "values", "isComplete", "error", "lastUpdate")(r =>
      (r.strategy, r.params, r.barSize, r.portfolio, r.trades, r.collections,
        r.timeSeries, r.values, r.isComplete, r.error, r.lastUpdate))

  implicit val reportDe: Decoder[Report] = Decoder.forProduct11[Report,
      String, Json, FiniteDuration, Portfolio,
      debox.Buffer[TradeEvent],
      debox.Map[String, debox.Buffer[Json]],
      debox.Map[String, CandleFrame],
      debox.Map[String, ReportValue[Any]],
      Boolean, Option[ReportError], MutableOpt[ReportDelta]
    ]("strategy", "params", "barSize", "portfolio", "trades", "collections",
      "timeSeries", "values", "isComplete", "error", "lastUpdate"
  )(new Report(_, _, _, _, _, _, _, _, _, _, _))

  def empty(strategyName: String,
            params: Json,
            barSize: Option[FiniteDuration] = None): Report = new Report(
    strategyName,
    params,
    barSize.getOrElse(1 hour),
    Portfolio.empty,
    debox.Buffer.empty,
    debox.Map.empty,
    debox.Map.empty,
    debox.Map.empty,
    isComplete = false,
    None,
    MutableOpt.from(None)
  )

  implicit val reportFmt: DeltaFmtJson[Report] =
    DeltaFmt.updateEventFmtJson[Report, ReportDelta]("report")
}

