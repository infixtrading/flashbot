package flashbot.engine

import java.time.Instant

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes._
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import flashbot.core.DataType.{LadderType, TradesType}
import flashbot.core.{DataType, MarketData, Trade}
import flashbot.util.time._
import flashbot.util._
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.generic.JsonCodec
import io.circe.generic.extras._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import flashbot.client.FlashbotClient
import flashbot.models.core.{Candle, DataPath, Ladder, TimeRange}

import scala.collection.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

object GrafanaServer {

  // Preferred ordering of columns. Columns not listed here are added to the end.
  val TradeCols = List("path", "time")

  val BidQty = "Bid Quantity"
  val BidPrice = "Bid Price"
  val AskPrice = "Ask Price"
  val AskQty = "Ask Quantity"
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
      val dataObj = md.data.asJson.asObject.get
      dataObj
        .filterKeys(_ != "micros")
        .add("time", (md.micros / 1000).asJson)
        .add("path", md.path.toString.asJson)
    }

  val askEncoder: ObjectEncoder[(Double, Double)] = ObjectEncoder.instance {
    case (price, qty) => JsonObject(AskPrice -> price.asJson, AskQty -> qty.asJson)
  }
  val bidEncoder: ObjectEncoder[(Double, Double)] = ObjectEncoder.instance {
    case (price, qty) => JsonObject(BidPrice -> price.asJson, BidQty -> qty.asJson)
  }

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

  def pathFromFilters(filters: Seq[Filter]): DataPath[_] = {
    val filterMap = filters.filter(_.operator == "=").map(f => f.key -> f.value).toMap
    DataPath(
      filterMap.getOrElse("source", "*"),
      filterMap.getOrElse("topic", "*"),
      DataType(filterMap.getOrElse("datatype", "*"))
    )
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

        val dataSetsFut = Future.sequence(body.targets.toIterator.map[Future[DataSeries]] { target =>
          target.target match {

            case "trades" =>
              for {
                streamSrc <- client.historicalMarketDataAsync[Trade](
                  pathFromFilters(body.adhocFilters).withType(TradesType),
                  Some(Instant.ofEpochMilli(fromMillis)),
                  Some(Instant.ofEpochMilli(toMillis)))
                tradeMDs <- streamSrc.runWith(Sink.seq)
              } yield buildTable(tradeMDs.reverse.take(body.maxDataPoints.toInt).map(_.asJsonObject), TradeCols)

            case "orderbook" =>
              for {
                streamSrc <- client.pollingMarketDataAsync[Ladder](
                  pathFromFilters(body.adhocFilters).withType(LadderType(Some(10))))
                ladder <- streamSrc.runWith(Sink.head)
              } yield buildTable(
                ladder.data.asks.map(_.asJsonObject(askEncoder)).toSeq ++
                  ladder.data.bids.map(_.asJsonObject(bidEncoder)).toSeq,
                LadderCols)

            case "price" =>
              val path = pathFromFilters(body.adhocFilters).withType(TradesType)
              client.pricesAsync(path, body.range, body.intervalMs millis)
                .map(buildSeries("price", s"local.${path.source}.${path.topic}", _))
          }
        })

        onSuccess(dataSetsFut) { dataSets =>
          val jsonRsp = dataSets.toSeq.asJson
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

  def buildSeries(target: String, seriesKey: String, seriesMap: Map[String, Seq[Candle]]): TimeSeries =
    {
      println(seriesMap.keySet)
      TimeSeries(target, seriesMap.getOrElse[Seq[Candle]](seriesKey, Seq.empty)
        .map(c => (c.close, c.micros / 1000)))
    }

}
