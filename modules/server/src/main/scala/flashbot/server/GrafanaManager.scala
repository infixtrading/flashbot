package flashbot.server

import akka.Done
import akka.actor.{Actor, ActorLogging}
import akka.http.scaladsl.model.ContentTypes
import GrafanaDashboard._
import flashbot.util._
import flashbot.util.json._
import io.circe.parser._
import io.circe.syntax._
import io.circe.optics.JsonPath._

import scala.concurrent.ExecutionContext

//import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.{Await, Future}
import com.softwaremill.sttp._
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import flashbot.core.{EngineLoader, StrategyInfo}
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

  implicit val ec: ExecutionContext = context.system.dispatcher
  implicit val okHttpBackend = OkHttpFutureBackend()

  private case object Init

  context.system.scheduler.scheduleOnce(1 second, self, Init)

  override def receive = {
    case Init =>
      val createdFut = for {
        dataSourceId <- ensureDataSource
        folderId <- ensureFolder
        markets <- loader.markets.map(_.toSeq)
        marketDataDash <- updateDashboard(folderId, "market_data", "Market Data",

          _.clearTemplates
            // Market variable
            .withTemplate(
              mkTemplate("market")
                .withLabelledOptions(markets.map(x => (x.toString, x.label)):_*)
                .fallbackSelected(markets
                  .find(m => m.exchange.toLowerCase == "coinbase" && m.symbol == "btc_usd")
                  .orElse(markets.headOption)
                  .map(_.toString))
                .copy(hide = 1))
            // Bar size interval
            .withTemplate(mkInterval())

            .clearPanels
            // Price panel
            .withPanel(mkPricePanel(1))
            // Trades panel
            .withPanel(mkTablePanel(2, "Trades", GridPos(0, 8, 12, 12))
              .withTarget(mkTableTarget("trades")
                .withField("market")))
            // Order book panel
            .withPanel(mkTablePanel(3, "Order Book", GridPos(12, 8, 12, 12))
              .withTarget(mkTableTarget("orderbook").withField("market")))
        )

        // Build the backtest dashboards
        infos <- strategyInfos
        _ <- Future.sequence(infos.toSeq.map {
          case (strategy, info) =>
            updateDashboard(folderId, strategy + "_backtest", "Backtest: " + info.title,
              info.layout.buildDashboard(_).withTag("backtest")
                .withTemplate(mkInterval())
                .withTemplate(mkTemplate("portfolio", "textbox", label = Some("Portfolio"))
                  .withOptions("coinbase.usd=5000")
                  .withSelected("coinbase.usd=5000"))
                .withJsonSchemaTemplates(info.jsonSchema.get)
                .mapPanels(_.mapTargets(
                  _.withField("bar_size")
                    .withField("portfolio")
                    .withField("strategy", strategy.asJson)
//                    .withField("params", ???)
                ))
            )
        })

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
      .body(entity.asJson.noNulls)
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

  def strategyInfos: Future[Map[String, StrategyInfo]] =
    loader.allStrategyInfos.map(_.filter(_._2.jsonSchema.isDefined))


  def flashbotDataSource: DataSource =
    DataSource(None, "flashbot", "simpod-json-datasource", "proxy",
      s"http://localhost:$dataSourcePort", basicAuth = false, isDefault = false)
}


