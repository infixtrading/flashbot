package flashbot.server

import akka.Done
import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.model.ContentTypes
import flashbot.server.GrafanaManager._
import flashbot.util._
import io.circe.parser._
import io.circe.syntax._
import io.circe.optics.JsonPath._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import com.softwaremill.sttp._
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import flashbot.core.SessionLoader
import io.circe.generic.JsonCodec
import io.circe.{Decoder, Encoder, Json}

import scala.util.{Failure, Success}

/**
  * This actor is created when a trading engine starts if a Grafana API key
  * is configured. On initialization, it creates a number of dashboards:
  *
  *   1. First is the shared "Markets" dashboard.
  *   2. Then it looks at all the configured strategies, and creates a backtest
  *      dashboard for each one based on it's `StrategyInfo`.
  *   3. Additionally, it creates a dashboard for every configured bot, regardless
  *      of it's `active` status. If it's not running, the dashboard simply
  *      displays it as "off".
  */
class GrafanaManager(host: String, apiKey: String,
                     sessionLoader: SessionLoader) extends Actor with ActorLogging {

  implicit val okHttpBackend = OkHttpFutureBackend()

  context.system.scheduler.scheduleOnce(1 second, self, Init)

  self ! Init

  override def receive = {
    case Init =>
      val createdFut = for {
        folderId <- ensureFolder
        marketDataDash = mkDashboard("market_data", "Market Data", 0,
          Seq(mkTemplate(name = "Market", current = mkCurrent("Coinbase:BTC_USD"))))
        _ <- updateDashboard(folderId, "market_data", "Market Data",
          x => marketDataDash)
      } yield marketDataDash

      createdFut onComplete {
        case Success(value) =>
          log.info("Created dasbboard: {}", value)
        case Failure(err) =>
          log.error(err, "Grafana Manager Error")
      }
  }

  def updateDashboard(folderId: Long, uid: String, title: String,
                      mkDashFn: Dashboard => Dashboard): Future[Unit] = for {
    existing <- get[DashboardRsp](s"api/dashboards/uid/$uid").map(_.map(_.dashboard))
    _ <- post[DashboardPost, DashboardPostRsp](s"api/dashboards/db",
      DashboardPost(
        mkDashFn(existing.getOrElse(mkDashboard(uid, title, 0, Seq()))),
        folderId,
        overwrite = false)
      )
  } yield Unit

  def ensureFolder: Future[Long] = for {
    folders <- get[Seq[Folder]]("api/folders").map(_.get)
    fbFolder = folders.find(_.title == "Flashbot")
    folderId <- fbFolder.map(f => Future.successful(f.id))
      .getOrElse(post[Folder, Folder]("api/folders", Folder("Flashbot")).map(_.id))
  } yield folderId.get

  def post[E: Encoder, T: Decoder](path: String, entity: E): Future[T] =
    sttp.post(uri"$host".path(path))
      .contentType("application/json")
      .body(entity.asJson.noSpaces)
      .headers(Map("Authorization" -> s"Bearer $apiKey"))
      .send()
      .flatMap(rsp => rsp.body match {
        case Left(err) => Future.failed(new RuntimeException(err))
        case Right(value) =>
          decode[T](value).toTry.toFut andThen {
            case Failure(err) =>
              println("A", rsp)
            case _ =>
          }
      })

  def get[T: Decoder](path: String): Future[Option[T]] =
    sttp.get(uri"$host".path(path))
      .headers(Map("Authorization" -> s"Bearer $apiKey"))
      .contentType("application/json")
      .send()
      .flatMap(rsp => rsp.body match {
        case Left(err) =>
          if (rsp.code == 404)
            Future.successful(None)
          else Future.failed(new RuntimeException(err))
        case Right(value) =>
          decode[T](value).toTry.toFut.map(Some(_)) andThen {
            case Failure(err) =>
              println("B", rsp)
            case _ =>
          }
      })

  def mkDashboard(uid: String, title: String, version: Long,
                  templates: Seq[Template]): Dashboard =
    Dashboard(None, uid, title, Seq("flashbot"), "browser", 16, 0,
      Some(Templating(templates)))

  def mkTemplate(`type`: String = "custom", name: String, current: VariableOption): Template =
    Template(None, `type`,
      VariableOption(Some(Seq()), "Coinbase:BTC_USD", "Coinbase:BTC_USD", None),
      0, includeAll = false, multi = false, name, None, Seq(
        VariableOption(None, "Coinbase:BTC_USD", "Coinbase:BTC_USD", None),
        VariableOption(None, "Coinbase:ETH_USD", "Coinbase:ETH_USD", None)
      ), "", skipUrlSync = false)

  def mkCurrent(value: String): VariableOption =
    VariableOption(Some(Seq()), value, value, None)

  def mkOption(value: String, selected: Boolean): VariableOption =
    VariableOption(None, value, value, Some(selected))

  def strategySchemas: Future[Map[String, Json]] =
    Future {
      sessionLoader.allStrategyInfos
        .map(_.mapValues(_.jsonSchema.map(parse(_).right.get)) collect {
          case (key, Some(schema)) => key -> schema
        } toMap)
    } flatten
}

object GrafanaManager {
  private case object Init

  @JsonCodec
  case class Folder(title: String, id: Option[Long] = None,
                    uid: Option[String] = None, url: Option[String] = None)

  @JsonCodec
  case class Dashboard(id: Option[Long], uid: String, title: String, tags: Seq[String],
                       timezone: String, schemaVersion: Long, version: Long,
                       templating: Option[Templating])

  @JsonCodec
  case class DashboardPost(dashboard: Dashboard, folderId: Long, overwrite: Boolean)

  @JsonCodec
  case class DashboardPostRsp(id: Long, url: String, status: String, version: Long)

  @JsonCodec
  case class Meta(url: String)

  @JsonCodec
  case class DashboardRsp(dashboard: Dashboard, meta: Meta)

  @JsonCodec
  case class VariableOption(tags: Option[Seq[String]], text: String, value: String,
                            selected: Option[Boolean])

  @JsonCodec
  case class Template(allValue: Option[String], `type`: String, current: VariableOption,
                      hide: Long, includeAll: Boolean, multi: Boolean, name: String,
                      label: Option[String], options: Seq[VariableOption], query: String,
                      skipUrlSync: Boolean) {
    def withCurrent(option: VariableOption): Template = copy(current = option)
    def withOption(option: VariableOption): Template = copy(options = options :+ option)
  }

  @JsonCodec
  case class Templating(list: Seq[Template])
}
