package com.infixtrading.flashbot.engine

import java.time.Instant

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes._
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import com.infixtrading.flashbot.client.FlashbotClient
import com.infixtrading.flashbot.core.{MarketData, Trade}
import com.infixtrading.flashbot.models.core.{DataPath, TimeRange}
import com.infixtrading.flashbot.util.time.TimeFmt
import io.circe.{Decoder, Json, JsonObject}
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.generic.JsonCodec
import io.circe.generic.extras._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object GrafanaServer {

  implicit val config: Configuration = Configuration.default
  implicit val timeRangeDecoder: Decoder[TimeRange] = Decoder.decodeJsonObject.map { obj =>
    val (from, to) = (for {
      from <- obj("from").map(_.as[String].right.get)
      to <- obj("to").map(_.as[String].right.get)
    } yield (from, to)).get
    TimeRange(TimeFmt.ISO8601ToMicros(from), TimeFmt.ISO8601ToMicros(to))
  }

  @ConfiguredJsonCodec case class Target(target: String, refId: String, @JsonKey("type") Type: String, data: Json)

  @JsonCodec case class Filter(key: String, operator: String, value: String)

  @ConfiguredJsonCodec case class Column(text: String, @JsonKey("type") Type: String)


  @JsonCodec sealed trait DataSeries

  @ConfiguredJsonCodec case class Table(columns: Seq[Column], rows: Seq[Seq[Json]], @JsonKey("type") Type: String) extends DataSeries

  @JsonCodec case class TimeSeries(target: String, datapoints: Seq[(Double, Long)]) extends DataSeries

  @JsonCodec case class Annotation(name: String, datasource: String, iconColor: String, enable: Boolean, query: String)

  @JsonCodec case class SearchReqBody(target: String)

  @JsonCodec case class QueryReqBody(panelId: Long, range: TimeRange, intervalMs: Long, maxDataPoints: Long,
                                     targets: Seq[Target], adhocFilters: Seq[Filter])

  @JsonCodec case class AnnotationReqBody(range: TimeRange, annotation: Annotation, variables: Seq[String])

  def pathFromFilters(filters: Seq[Filter]): DataPath = {
    val filterMap = filters.filter(_.operator == "=").map(f => f.key -> f.value).toMap
    DataPath(
      filterMap.getOrElse("source", "*"),
      filterMap.getOrElse("topic", "*"),
      filterMap.getOrElse("datatype", "*")
    )
  }

  def routes(client: FlashbotClient)(implicit mat: Materializer): Route = get {
    pathSingleSlash {
      complete(HttpEntity(ContentTypes.`application/json`, "{}"))
    }
  } ~ post {
    path("search") {
      entity(as[SearchReqBody]) { body =>
        val rsp = Seq("trades")
        complete(HttpEntity(ContentTypes.`application/json`, rsp.asJson.noSpaces ))
      }
    } ~ path("query") {
      entity(as[QueryReqBody]) { body =>

        val fromMillis = body.range.start
        val toMillis = body.range.end

        val dataSetsFut = Future.sequence(body.targets.toIterator.map[Future[DataSeries]] { target =>
          target.target match {
            case "trades" =>
              for {
                streamSrc <- client.historicalMarketDataAsync[Trade](
                  pathFromFilters(body.adhocFilters),
                  Some(Instant.ofEpochMilli(fromMillis)),
                  Some(Instant.ofEpochMilli(toMillis)))
                tradeMDs <- streamSrc.take(body.maxDataPoints).runWith(Sink.seq)
              } yield buildTable(tradeMDs.map(_.data.asJson.asObject.get))
          }
        })

        onSuccess(dataSetsFut) { dataSets =>
          complete(HttpEntity(ContentTypes.`application/json`, dataSets.toSeq.asJson.noSpaces ))
        }
      }
    } ~ path("annotations") {
      entity(as[AnnotationReqBody]) { body =>
        complete(HttpEntity(ContentTypes.`application/json`, body.asJson.noSpaces ))
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
    var cols = Vector.empty[Column]
    for (o <- objects.take(100)) {
      for (key <- o.keys.toSet[String].diff(cols.toSet[Column].map(_.Type))) {
        val jsonVal = o(key).get
        val ty = inferJsonType(key, jsonVal)
        if (ty.isDefined) {
          cols :+ Column(key, ty.get)
        }
      }
    }
    cols
  }

  def buildTable(objects: Seq[JsonObject]): Table = {
    var cols = buildCols(objects)
    var rows: Seq[Seq[Json]] = objects.map(o => cols.map(col => o(col.text).asJson ))
    Table(cols, rows, "table")
  }

}
