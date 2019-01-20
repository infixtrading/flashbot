package com.infixtrading.flashbot.engine

import akka.actor.ActorRef
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes._
import com.infixtrading.flashbot.models.core.TimeRange
import com.infixtrading.flashbot.util.time.TimeFmt
import io.circe.{Decoder, Json}
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.generic.JsonCodec
import io.circe.generic.extras._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._

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
  @ConfiguredJsonCodec case class Table(columns: Seq[Column], rows: Seq[Seq[Json]], @JsonKey("type") Type: String)

  @JsonCodec case class TimeSeries(target: String, datapoints: Seq[(Double, Long)])

  @JsonCodec case class Annotation(name: String, datasource: String, iconColor: String, enable: Boolean, query: String)

  @JsonCodec case class SearchReqBody(target: String)

  @JsonCodec case class QueryReqBody(panelId: Long, range: TimeRange, intervalMs: Long, maxDataPoints: Long,
                                     targets: Seq[Target], adhocFilters: Seq[Filter])

  @JsonCodec case class AnnotationReqBody(range: TimeRange, annotation: Annotation, variables: Seq[String])

  def routes(engine: ActorRef): Route = get {
    pathSingleSlash {
      complete(HttpEntity(ContentTypes.`application/json`, "{}"))
    }
  } ~ post {
    path("search") {
      entity(as[SearchReqBody]) { body =>
        val rsp = Seq("foo", "bar")
        complete(HttpEntity(ContentTypes.`application/json`, rsp.asJson.noSpaces ))
      }
    } ~ path("query") {
      entity(as[QueryReqBody]) { body =>
        val now = System.currentTimeMillis()
        val tableRsp = Seq(Table(Seq(
          Column("Time", "time"),
          Column("Country", "string"),
          Column("Population", "number")
        ), Seq(
          Seq(now.asJson, "SE".asJson, 123.asJson),
          Seq((now + 1000).asJson, "US".asJson, 456.asJson),
          Seq((now + 2000).asJson, "RU".asJson, 789.asJson)
        ), "table"))
        complete(HttpEntity(ContentTypes.`application/json`, tableRsp.asJson.noSpaces ))
      }
    } ~ path("annotations") {
      entity(as[AnnotationReqBody]) { body =>
        complete(HttpEntity(ContentTypes.`application/json`, body.asJson.noSpaces ))
      }
    }
  }
}
