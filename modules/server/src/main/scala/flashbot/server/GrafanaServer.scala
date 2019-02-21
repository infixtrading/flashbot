package flashbot.server

import java.time.Instant

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes._
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import flashbot.core.DataType.{LadderType, TradesType}
import flashbot.core.{DataType, MarketData, Report, Trade}
import flashbot.util.time._
import flashbot.util._
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import io.circe.generic.auto._
import io.circe.generic.JsonCodec
import io.circe.generic.extras._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import flashbot.client.FlashbotClient
import flashbot.models.api.TakeLast
import flashbot.models.core._

import scala.collection.SortedMap
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.Failure

object GrafanaServer {

  // Preferred ordering of columns. Columns not listed here are added to the end.
  val TradeCols = List("path", "time")

  val BidQty = "Bid Quantity".toLowerCase
  val BidPrice = "Bid Price".toLowerCase
  val AskPrice = "Ask Price".toLowerCase
  val AskQty = "Ask Quantity".toLowerCase
  val LadderCols = List(BidQty, BidPrice, AskPrice, AskQty)

  implicit val config: Configuration = Configuration.default
  implicit val timeRangeDecoder: Decoder[TimeRange] = Decoder.decodeJsonObject.map { obj =>
    val (from, to) = (for {
      from <- obj("from").map(_.as[String].right.get)
      to <- obj("to").map(_.as[String].right.get)
    } yield (from, to)).get
    TimeRange(TimeFmt.ISO8601ToMicros(from), TimeFmt.ISO8601ToMicros(to))
  }

  implicit def mdEncoder[T](implicit tEn: Encoder[T]): ObjectEncoder[MarketData[T]] =
    Encoder.encodeJsonObject.contramapObject { md =>
      var dataObj = md.data.asJson.asObject.get
      dataObj = dataObj
        .filterKeys(_ != "micros")
        .add("time", (md.micros / 1000).asJson)
        .add("path", md.path.toString.asJson)
      dataObj
    }

  val askEncoder: ObjectEncoder[(Double, Double)] = ObjectEncoder.instance {
    case (price, qty) => JsonObject(AskPrice -> price.asJson, AskQty -> qty.asJson)
  }
  val bidEncoder: ObjectEncoder[(Double, Double)] = ObjectEncoder.instance {
    case (price, qty) => JsonObject(BidPrice -> price.asJson, BidQty -> qty.asJson)
  }

  JsonObject()

  @ConfiguredJsonCodec case class Target(target: String, refId: String, @JsonKey("type") Type: String, data: Json)

  @JsonCodec case class Filter(key: String, operator: String, value: String)

  @ConfiguredJsonCodec case class Column(text: String, @JsonKey("type") Type: String,
                                         sort: Boolean = false, desc: Boolean = false)

  sealed trait DataSeries

  implicit val en: Encoder[DataSeries] = Encoder.encodeJsonObject.contramap {
    case ts: TimeSeries => ts.asJsonObject
    case table: Table => table.asJsonObject
  }

  @ConfiguredJsonCodec case class Table(columns: Seq[Column], rows: Seq[Seq[Json]], @JsonKey("type") Type: String) extends DataSeries

  @JsonCodec case class TimeSeries(target: String, datapoints: Seq[(Double, Long)]) extends DataSeries

  @JsonCodec case class Annotation(name: String, datasource: String, iconColor: String, enable: Boolean, query: String)

  @JsonCodec case class SearchReqBody(target: String)

  @JsonCodec case class QueryReqBody(panelId: Long, range: TimeRange, intervalMs: Long, maxDataPoints: Long,
                                     targets: Seq[Target], adhocFilters: Seq[Filter])

  @JsonCodec case class AnnotationReqBody(range: TimeRange, annotation: Annotation, variables: Seq[String])

  @ConfiguredJsonCodec case class TagKey(@JsonKey("type") Type: String, text: String)
  @JsonCodec case class TagValue(text: String)

  @JsonCodec case class TagValReq(key: String)

  @JsonCodec case class ParamValue(value: String, jsonType: String, required: Boolean)

  @JsonCodec case class Query(key: String, `type`: String, market: Option[String],
                              bar_size: Option[String], strategy: Option[String],
                              params: Option[Map[String, ParamValue]], bot: Option[String],
                              portfolio: Option[String])

  def paramsToJson(params: Map[String, ParamValue]): Json = params.foldLeft(JsonObject()) {
    case (obj, (key, ParamValue(value, jsonType, required))) =>
      def foldParam[T: Decoder: Encoder](filter: T => Boolean = _ => true) =
        decode[T](value.trim).toOption.filter(filter) match {
          case Some(v) => obj.add(key, v.asJson)
          case None if !required => obj
          case None => throw new RuntimeException(s"Unable to parse $key value \"$value\" as $jsonType")
        }

      jsonType match {
        case "integer" =>
          foldParam[Long]()
        case "number" =>
          foldParam[Double]()
        case "object" =>
          throw new RuntimeException("Object param types not yet supported by GrafanaServer.")
        case "array" =>
          throw new RuntimeException("Array param types not yet supported by GrafanaServer.")
        case "boolean" =>
          foldParam[Boolean]()
        case "string" =>
          foldParam[String](_.nonEmpty)
      }
  }.asJson

  def pathFromFilters(filters: Seq[Filter]): DataPath[_] = {
    val filterMap = filters.filter(_.operator == "=").map(f => f.key -> f.value).toMap
    DataPath(
      filterMap.getOrElse("source", "*"),
      filterMap.getOrElse("topic", "*"),
      DataType(filterMap.getOrElse("datatype", "*"))
    )
  }

  case class BacktestCacheKey(strategy: String, params: Json,
                              portfolio: String, interval: FiniteDuration,
                              timeRange: TimeRange)

  val backtestRequests = TrieMap.empty[BacktestCacheKey, Future[Report]]
  def getBacktestReport(client: FlashbotClient, key: BacktestCacheKey): Future[Report] = {
    val fut = backtestRequests.getOrElseUpdate(key, client.backtestAsync(
      key.strategy, key.params, key.portfolio, key.interval, key.timeRange))
    fut andThen {
      case scala.util.Success(value) =>
        // Remove reports for all cache keys that don't equal the current one, but
        // do belong to the same strategy.
        backtestRequests
          .filterKeys(k => k.strategy == key.strategy && k != key)
          .foreach(Function.tupled(backtestRequests.remove))

      case Failure(_) =>
        // Always remove failed requests from the cache.
        backtestRequests.remove(key, fut)
    }
    fut
  }


  def routes(client: FlashbotClient)(implicit mat: Materializer): Route = get {
    pathSingleSlash {
      complete(HttpEntity(ContentTypes.`application/json`, "{}"))
    }
  } ~ post {
    path("search") {
      entity(as[SearchReqBody]) { body =>
        val rsp = Seq("trades", "price", "orderbook")
        complete(HttpEntity(ContentTypes.`application/json`, rsp.asJson.noSpaces ))
      }
    } ~ path("query") {
      entity(as[QueryReqBody]) { body =>

        val fromMillis = body.range.start / 1000
        val toMillis = body.range.end / 1000

        val dataSetsFut = Future.sequence(body.targets.toIterator.map[Future[Seq[DataSeries]]] { target =>
          decode[Query](target.target).toTry match {
            case scala.util.Success(
              Query(key, ty, marketOpt, barSizeOpt, strategyOpt, paramsOpt, botOpt, portfolioOpt)) =>
              (ty, key, strategyOpt) match {

                case ("time_series", _, Some(strategy)) =>
                  val barSize = parseDuration(barSizeOpt.get)
                  val cacheKey = BacktestCacheKey(strategy, paramsToJson(paramsOpt.get),
                    portfolioOpt.get, barSize, body.range)
                  getBacktestReport(client, cacheKey).map(report =>
                    Seq(buildSeries(key, key, report.timeSeries)))

                case ("table", _, Some(strategy)) =>
                  val barSize = parseDuration(barSizeOpt.get)
                  val cacheKey = BacktestCacheKey(strategy, paramsToJson(paramsOpt.get),
                    portfolioOpt.get, barSize, body.range)
                  getBacktestReport(client, cacheKey).map(report => {
                    if (report.collections.isDefinedAt(key))
                      Seq(buildTable(report.collections(key).map(_.asJsonObject), List()))
                    else if (report.values.isDefinedAt(key))
                      Seq(buildTable(Seq(JsonObject("value" -> report.values(key).asJson)), List()))
                    else Seq(buildTable(Seq(JsonObject("error" -> "no data".asJson)), List()))
                  })

                case (_, "trades", _) =>
                  val path = DataPath.wildcard.withType(TradesType).withMarket(marketOpt.get)
                  for {
                    streamSrc <- client.historicalMarketDataAsync[Trade](path,
                      Some(Instant.ofEpochMilli(fromMillis)),
                      Some(Instant.ofEpochMilli(toMillis)),
                      Some(TakeLast(body.maxDataPoints.toInt))
                    )
                    tradeMDs <- streamSrc.runWith(Sink.seq)
                  } yield Seq(buildTable(tradeMDs.reverse.map(_.asJsonObject), TradeCols))

                case (_, "orderbook", _) =>
                  val path = DataPath.wildcard.withType(LadderType(Some(12))).withMarket(marketOpt.get)
                  for {
                    streamSrc <- client.pollingMarketDataAsync[Ladder](path)
                    ladder <- streamSrc.runWith(Sink.head)
                    askObjects = ladder.data.asks.toSeq.map(_.asJsonObject(askEncoder))
                    bidObjects = ladder.data.bids.toSeq.map(_.asJsonObject(bidEncoder))
                    t = buildTable(
                      askObjects.zipAll(bidObjects, JsonObject(), JsonObject())
                        .map(Function.tupled(mergeObjects)),
                      LadderCols)
                  } yield Seq(t)

                case (_, "price", _) =>
                  val path = DataPath.wildcard.withType(TradesType).withMarket(marketOpt.get)
                  val barSize = parseDuration(barSizeOpt.get)
                  client.pricesAsync(path, body.range, barSize)
                    .map(ts => Seq(
                      buildSeries("price", s"${path.source}.${path.topic}", ts),
                      buildSeries("volume", s"${path.source}.${path.topic}", ts,
                        _.volume, scaleTo(0, 1))))
              }

            case Failure(exception) => Future.failed(exception)
          }
        })

        onSuccess(dataSetsFut) { dataSets =>
          val jsonRsp = dataSets.toSeq.flatten.asJson
          complete(HttpEntity(ContentTypes.`application/json`, jsonRsp.noSpaces ))
        }
      }
    } ~ path("annotations") {
      entity(as[AnnotationReqBody]) { body =>
        complete(HttpEntity(ContentTypes.`application/json`, body.asJson.noSpaces ))
      }
    } ~ path("tag-keys") {
      val keys = Seq(TagKey("string", "source"), TagKey("string", "topic"))
      complete(HttpEntity(ContentTypes.`application/json`, keys.asJson.noSpaces ))

    } ~ path("tag-values") {
      entity(as[TagValReq]) { req =>
        val vals = req.key match {
          case "source" => Seq(TagValue("coinbase"), TagValue("bitstamp"))
          case "topic" => Seq(TagValue("btc_usd"), TagValue("eth_usd"))
        }
        complete(HttpEntity(ContentTypes.`application/json`, vals.asJson.noSpaces ))
      }
    }
  }

  def inferJsonType(key: String, value: Json): Option[String] = {
    if ((key == "time" || key == "micros") && (value.isNull || value.isNumber)) {
      Some("time")
    } else if (value.isNumber) {
      Some("number")
    } else if (value.isString) {
      Some("string")
    } else None
  }

  def buildCols(objects: Seq[JsonObject]): Seq[Column] = {
    objects.flatMap(o => o.keys.map(key => key -> inferJsonType(key, o(key).get)))
      .collect {
//        case ("time", Some(ty)) => ("time", Column("time", ty, sort = true, desc = true))
        case (k, Some(ty)) => (k, Column(k, ty))
      }.toMap.values.toSeq
  }

  def sortCols(cols: Seq[Column], colOrder: List[String]): Seq[Column] = {
    val byKey = cols.sortBy(_.text)
    val preferred = colOrder.map(c => byKey.find(_.text == c)) collect { case Some(x) => x }
    val rest = byKey.filterNot(colOrder contains _.text)
    preferred ++ rest
  }

  def buildTable(objects: Seq[JsonObject], colOrder: List[String]): Table = {
    var cols = sortCols(buildCols(objects), colOrder)
    var rows: Seq[Seq[Json]] = objects.map(o =>
      cols.map(col => o(col.text).asJson ))
    Table(cols, rows, "table")
  }

  def buildSeries(target: String, seriesKey: String,
                  seriesMap: Map[String, Seq[Candle]],
                  valFn: Candle => Double = _.close,
                  transform: Seq[Double] => Seq[Double] = x => x): TimeSeries =
    {
      val (values, times) = seriesMap
        .getOrElse[Seq[Candle]](seriesKey, Seq.empty)
        .map(c => (valFn(c), c.micros / 1000)).unzip
      TimeSeries(target, transform(values).zip(times))
    }

  def mergeObjects(a: JsonObject, b: JsonObject): JsonObject =
    b.toIterable.foldLeft(a) {
      case (memo, (key, value)) => memo.add(key, value)
    }

  def scaleTo(lower: Double, upper: Double) = transformation(values => {
    val min = values.min
    val max = values.max
    val scale = (upper - lower) / (max - min)
    values.map(v => v * scale - min * scale)
  })

  def transformation(fn: Seq[Double] => Seq[Double]): Seq[Double] => Seq[Double] =
    values => if (values.isEmpty) values else fn(values)

}
