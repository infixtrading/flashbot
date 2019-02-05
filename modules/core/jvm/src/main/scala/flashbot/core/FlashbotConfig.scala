package flashbot.core

import com.typesafe.config.{Config, ConfigFactory}
import flashbot.models.core._
import flashbot.util.time
import io.circe.{Decoder, Encoder, HCursor, Json}
import io.circe.generic.extras._
import io.circe.config.syntax._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try
import FlashbotConfig._

@ConfiguredJsonCodec(decodeOnly = true)
case class FlashbotConfig(engineRoot: String,
                          systemName: String,
                          ingest: IngestConfig,
                          strategies: Map[String, String],
                          exchanges: Map[String, ExchangeConfig],
                          sources: Map[String, DataSourceConfig],
                          bots: StaticBotsConfig,
                          grafana: GrafanaConfig,
                          conf: Config,
                          db: Config) {
  def noIngest = copy(ingest = ingest.copy(enabled = Seq.empty))
}

object FlashbotConfig {
  implicit val jsonConfig: Configuration = Configuration.default.withKebabCaseMemberNames

  implicit val durationDecoder: Decoder[FiniteDuration] = time.DurationDecoder

  val DefaultTTL = 0 seconds

  @ConfiguredJsonCodec(decodeOnly = true)
  case class IngestConfig(enabled: Seq[String], backfill: Seq[String], retention: Seq[Seq[String]]) {
    def ingestMatchers: Set[DataPath[Any]] = enabled.toSet.map(DataPath.parse)
    def backfillMatchers: Set[DataPath[Any]] = backfill.toSet.map(DataPath.parse)

    def filterIngestSources(sources: Set[String]) = sources.filter(src =>
      ingestMatchers.exists(_.matches(s"$src/*/*")))
    def filterBackfillSources(sources: Set[String]) = sources.filter(src =>
      backfillMatchers.exists(_.matches(s"$src/*/*")))
    def filterSources(sources: Set[String]) =
      filterIngestSources(sources) ++ filterBackfillSources(sources)

    def retentionFor(path: DataPath[_]): Option[FiniteDuration] =
      retention.find(record => DataPath(record.head).matches(path))
        .map(record => time.parseDuration(record.last))
  }

  @ConfiguredJsonCodec(decodeOnly = true)
  case class ExchangeConfig(`class`: String, params: Option[Json], pairs: Option[Seq[String]])

  @ConfiguredJsonCodec(decodeOnly = true)
  case class BotConfig(strategy: String,
                       mode: TradingSessionMode,
                       params: Json = Json.obj(),
                       ttl: FiniteDuration = DefaultTTL,
                       initialAssets: Map[String, Double] = Map.empty,
                       initialPositions: Map[String, Position] = Map.empty) {
    def ttlOpt: Option[Duration] = ttl match {
      case DefaultTTL => None
      case other => Some(other)
    }
  }

  @ConfiguredJsonCodec(decodeOnly = true)
  case class StaticBotsConfig(enabled: Seq[String], configs: Map[String, BotConfig]) {
    def enabledConfigs: Map[String, BotConfig] = configs.filterKeys(enabled contains _)
  }

  @ConfiguredJsonCodec(decodeOnly = true)
  case class GrafanaConfig(dataSourcePort: Option[Int], host: String, apiKey: Option[String])

  @ConfiguredJsonCodec(decodeOnly = true)
  case class DataSourceConfig(`class`: String, topics: Option[Seq[String]], datatypes: Option[Seq[String]])




  def tryLoad(key: String, appName: String, standalone: Boolean): Try[FlashbotConfig] = {
    val overrides = ConfigFactory.defaultOverrides()
    val apps = ConfigFactory.parseResources(s"$appName.conf")
    val refs = ConfigFactory.parseResources("reference.conf")

    var fbAkkaConf = refs.getConfig(key).withOnlyPath("akka")
    if (!standalone) {
      fbAkkaConf = refs.getConfig(s"$key.akka-cluster").atKey("akka").withFallback(fbAkkaConf)
    }

    var conf = overrides
      // Initial fallback is to `application.conf`
      .withFallback(apps)
      // We want `flashbot.akka` reference to override `akka` reference.
      .withFallback(fbAkkaConf)
      // Then we fallback to default references.
      .withFallback(refs.withoutPath(s"$key.akka").withoutPath(s"$key.akka-cluster"))
      // Finally, resolve.
      .resolve()

    // Replace db with the chosen config.
    val dbOverride = conf.getConfig(conf.getString(s"$key.db"))
    conf = dbOverride.atPath(s"$key.db").withFallback(conf)

    // Place full copy of the config into "flashbot.conf".
    conf = conf.withoutPath(key).atPath(s"$key.conf").withFallback(conf)

    // Decode into a FlashbotConfig
    conf.getConfig(key).as[FlashbotConfig].toTry
  }

  def load(appName: String = "application"): FlashbotConfig =
    tryLoad("flashbot", appName, standalone = false).get

  def loadStandalone(appName: String = "application"): FlashbotConfig =
    tryLoad("flashbot", appName, standalone = true).get
}

