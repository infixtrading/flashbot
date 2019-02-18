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
import flashbot.core.EngineLoader
import flashbot.core.Instrument.CurrencyPair
import io.circe.generic.JsonCodec
import io.circe.{Decoder, Encoder, Json, JsonObject}

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
class GrafanaManager(host: String, apiKey: String, dataSourcePort: Int,
                     loader: EngineLoader) extends Actor with ActorLogging {

  implicit val okHttpBackend = OkHttpFutureBackend()

  context.system.scheduler.scheduleOnce(1 second, self, Init)

  self ! Init

  override def receive = {
    case Init =>
      val createdFut = for {
        dataSourceId <- ensureDataSource
        folderId <- ensureFolder
        markets <- loader.markets.map(_.toSeq)
        marketDataDash <- updateDashboard(folderId, "market_data", "Market Data",
          _.withTemplate(
            mkTemplate(name = "market")
              .withLabelledOptions(markets.map(x => (x.toString, x.label)):_*)
              .fallbackSelected(markets
                .find(m => m.exchange.toLowerCase == "coinbase" && m.symbol == "btc_usd")
                .orElse(markets.headOption)
                .map(_.toString))
              .copy(hide = 1))
            .withTemplate(mkInterval())
            // Price panel
//            .withPanel(mkPricePanel())
            // Trades panel
            .withPanel(mkTablePanel(1, "Trades", GridPos(0, 12, 12, 12))
              .withTarget(mkTableTarget("trades").withField("market")))
            // Order book panel
//            .withPanel(mkTablePanel(2, "Order Book", GridPos(12, 12, 12, 12))
//              .withTarget(mkTableTarget("orderbook")))
        )
      } yield marketDataDash

      createdFut onComplete {
        case Success(value) =>
          log.info("Created dasbboard: {}", value)
        case Failure(err) =>
          log.error(err, "Grafana Manager Error")
      }
  }

  def updateDashboard(folderId: Long, uid: String, title: String,
                      mkDashFn: Dashboard => Dashboard): Future[Dashboard] = for {

    existing <- get[DashboardRsp](s"api/dashboards/uid/$uid").map(_.map(_.dashboard))
    dash = mkDashFn(existing.getOrElse(mkDashboard(uid, title, Seq())))
      .copy(version = existing.map(_.version).getOrElse(0))
    payload = DashboardPost(dash,
        folderId,
        overwrite = false)
    _ <- post[DashboardPost, DashboardPostRsp](s"api/dashboards/db", payload)
  } yield dash

  def ensureDataSource: Future[Long] = for {
    idOpt <- get[DataSourceId]("api/datasources/id/flashbot")
    id: Long <- idOpt.map(x => Future.successful(x.id)).getOrElse[Future[Long]](
      post[DataSource, DataSource]("api/datasources", flashbotDataSource).map(_.id.get))
  } yield id

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

  def strategySchemas: Future[Map[String, Json]] =
    Future {
      loader.allStrategyInfos
        .map(_.mapValues(_.jsonSchema.map(parse(_).right.get)) collect {
          case (key, Some(schema)) => key -> schema
        } toMap)
    } flatten


  def flashbotDataSource: DataSource =
    DataSource(None, "flashbot", "simpod-json-datasource", "proxy",
      s"http://localhost:$dataSourcePort", basicAuth = false, isDefault = false)
}

object GrafanaManager {
  private case object Init

  @JsonCodec
  case class Folder(title: String, id: Option[Long] = None,
                    uid: Option[String] = None, url: Option[String] = None)

  @JsonCodec
  case class Dashboard(id: Option[Long], uid: String, title: String, tags: Seq[String],
                       timezone: String, schemaVersion: Long, version: Long,
                       templating: Option[Templating], panels: Option[Seq[Panel]]) {
    def withPanel(panel: Panel): Dashboard =
      copy(panels = Some(panels.getOrElse(Seq.empty) :+ panel))

    def mapTemplate(name: String, updater: Template => Template): Dashboard =
      copy(templating = templating.map(_.mapTemplate(name, updater)))

    def withTemplate(template: Template): Dashboard =
      copy(templating = Some(templating.getOrElse(Templating(Seq.empty))
        .withTemplate(template)))
  }

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
  case class Template(allValue: Option[String], `type`: String, current: Option[VariableOption],
                      hide: Long, includeAll: Boolean, multi: Boolean, name: String,
                      label: Option[String], options: Seq[VariableOption], query: String,
                      refresh: Option[Int], skipUrlSync: Boolean, auto: Option[Boolean],
                      auto_count: Option[Boolean], auto_min: Option[String]) {
    // Add the option and update the query
    def withOption(option: VariableOption): Template =
      copy(options = options :+ option, query = options.map(_.value).mkString(","))

    def withOptions(values: String*): Template =
      values.map(mkOption(_)).foldLeft(this)(_.withOption(_))

    def withLabelledOptions(labelledVals: (String, String)*): Template =
      labelledVals.map(x => mkOption(x._1, Some(x._2))).foldLeft(this)(_.withOption(_))

    def withSelected(valueOpt: Option[String]): Template =
      valueOpt.map(withSelected).getOrElse(this)

    def withSelected(value: String): Template = {
      // Find the option with the given value. It must exist.
      val idx = options.indexWhere(_.value == value)
      val opt = options(idx)

      // Set that option to selected = true, and all others to false
      val newOptions = options.map(_.copy(selected = Some(false)))
        .updated(idx, opt.copy(selected = Some(true)))

      // Also set it as the "current".
      copy(options = newOptions, current = Some(mkCurrent(value, Some(opt.text))))
    }

    def fallbackSelected(value: Option[String]): Template =
      withSelected(selected.orElse(value))

    def selected: Option[String] = current.map(_.value)
  }

  @JsonCodec
  case class Templating(list: Seq[Template]) {
    def withTemplate(tmp: Template): Templating = {
      list.indexWhere(_.name == tmp.name) match {
        case -1 => copy(list = list :+ tmp)
        case i => copy(list = list.updated(i, tmp))
      }
    }

    def mapTemplate(name: String, fn: Template => Template): Templating =
      list.find(_.name == name).map(x => withTemplate(fn(x))).getOrElse(this)
  }

  @JsonCodec
  case class DataSource(id: Option[Long], name: String, `type`: String, access: String,
                        url: String, basicAuth: Boolean, isDefault: Boolean)

  @JsonCodec
  case class DataSourceId(id: Long)

  @JsonCodec
  case class Panel(columns: Option[Seq[Json]], datasource: String, fontSize: Option[String],
                   gridPos: GridPos, id: Long, links: Seq[Json], pageSize: Option[Json],
                   scroll: Option[Boolean], showHeader: Option[Boolean], sort: Option[Sort],
                   styles: Option[Seq[Style]], targets: Seq[Target], title: String, `type`: String,
                   transform: Option[String]) {
    def withTarget(target: Target): Panel = copy(targets = targets :+ target)
  }

  @JsonCodec
  case class Style(alias: String, dateFormat: Option[String], pattern: String, `type`: String,
                   colors: Option[Seq[String]], decimals: Option[Int], thresholds: Option[Seq[Json]],
                   unit: Option[String])

  @JsonCodec
  case class Target(data: String, hide: Boolean, refId: String, target: String, `type`: String,
                    expr: Option[String], format: Option[String], intervalFactor: Option[Int]) {
    def withField(field: String): Target = copy(target =
      decode[JsonObject](target).toOption.getOrElse(JsonObject())
        .add(field, ("$" + field).asJson).asJson.noSpaces)
  }

  @JsonCodec
  case class Sort(col: Option[String], desc: Boolean)

  @JsonCodec
  case class GridPos(x: Int, y: Int, w: Int, h: Int)

  val defaultStyle = Style("", None, "/.*/", "number", Some(Seq(
    "rgba(245, 54, 54, 0.9)",
    "rgba(237, 129, 40, 0.89)",
    "rgba(50, 172, 45, 0.97)"
  )), Some(2), Some(Seq.empty), Some("short"))

  val timeStyle = Style("Time", Some("YYYY-MM-DD HH:mm:ss"), "Time",
    "date", None, None, None, None)

  val defaultStyles = Seq(timeStyle, defaultStyle)

  def mkCurrent(value: String, label: Option[String] = None): VariableOption =
    VariableOption(Some(Seq()), label.getOrElse(value), value, None)

  def mkDashboard(uid: String, title: String,
                  templates: Seq[Template]): Dashboard =
    Dashboard(None, uid, title, Seq("flashbot"), "browser", 16, 0,
      Some(Templating(templates)), Some(Seq.empty))

  def mkTemplate(`type`: String = "custom", name: String, label: Option[String] = None): Template =
    Template(None, `type`, None,
      0, includeAll = false, multi = false, name, label, Seq(),
      "", refresh = Some(2), skipUrlSync = false, None, None, None)

  def mkOption(value: String, text: Option[String] = None, selected: Boolean = false): VariableOption =
    VariableOption(None, text.getOrElse(value), value, Some(selected))

  def mkInterval(name: String = "bar_size"): Template =
    mkTemplate("interval", name)
    .withOptions("1m", "10m", "30m", "1h", "6h", "12h", "1d", "7d", "14d", "30d")
    .withSelected("1h")
    .copy(hide = 1)

  def mkTablePanel(id: Long, title: String, gridPos: GridPos): Panel =
    Panel(Some(Seq.empty), "flashbot", Some("100%"), gridPos, id, Seq.empty, None,
      Some(true), Some(true), Some(Sort(None, desc = false)), Some(defaultStyles),
      Seq.empty, title, "table", Some("table"))

  def mkGraphPanel(id: Long, title: String, gridPos: GridPos): Panel =
    Panel(None, "flashbot", None, gridPos, id, Seq.empty, None,
      None, None, None, None, Seq.empty, title, "graph", None)

  def mkPricePanel(id: Long = 0, gridPos: GridPos = GridPos(0, 0, 24, 10)): Panel =
    mkGraphPanel(id, "Price", gridPos)
      .withTarget(mkGraphTarget("price").withField("market"))

  def mkTableTarget(key: String): Target = Target(
    "", hide = false, "A", Json.obj("key" -> key.asJson).noSpaces,
    "table", Some(""), Some("table"), Some(1))

  def mkGraphTarget(key: String): Target = Target(
    "", hide = false, "A", Json.obj("key" -> key.asJson).noSpaces,
    "timeseries", None, None, None)
}
