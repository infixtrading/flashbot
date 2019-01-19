package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, ExchangeConfig, IngestConfig, StaticBotsConfig}
import com.infixtrading.flashbot.models.core.{DataPath, Position}
import com.typesafe.config.{Config, ConfigFactory}
import io.circe._
import io.circe.config.syntax._
import io.circe.generic.semiauto._
import io.circe.generic.auto._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

case class FlashbotConfig(`engine-root`: String,
                          ingest: IngestConfig,
                          strategies: Map[String, String],
                          exchanges: Map[String, ExchangeConfig],
                          sources: Map[String, DataSourceConfig],
                          bots: StaticBotsConfig,
                          akka: Config,
                          db: Config) {
  def noIngest = copy(ingest = ingest.copy(enabled = Seq.empty))
}

object FlashbotConfig {

  case class IngestConfig(enabled: Seq[String], backfill: Seq[String], retention: Seq[Seq[String]]) {
    def ingestMatchers: Set[DataPath] = enabled.toSet.map(DataPath.parse)
    def backfillMatchers: Set[DataPath] = backfill.toSet.map(DataPath.parse)

    def filterIngestSources(sources: Set[String]) = sources.filter(src =>
      ingestMatchers.exists(_.matches(s"$src/*/*")))
    def filterBackfillSources(sources: Set[String]) = sources.filter(src =>
      backfillMatchers.exists(_.matches(s"$src/*/*")))
    def filterSources(sources: Set[String]) =
      filterIngestSources(sources) ++ filterBackfillSources(sources)
  }

  case class ExchangeConfig(`class`: String, params: Option[Json], pairs: Option[Seq[String]])

  implicit val pe: Encoder[Position] = Position.postionEn
  implicit val pd: Decoder[Position] = Position.postionDe

  // Required for deriving json encoders and decoders for anything like BotConfig
  // that includes Duration.
  import com.infixtrading.flashbot.util.time._

  val DefaultTTL = 0 seconds
  case class BotConfig(strategy: String,
                       mode: TradingSessionMode,
                       params: Json = Json.obj(),
                       ttl: Duration = DefaultTTL,
                       `initial-assets`: Map[String, Double] = Map.empty,
                       `initial-positions`: Map[String, Position] = Map.empty) {
    def ttlOpt: Option[Duration] = ttl match {
      case DefaultTTL => None
      case other => Some(other)
    }
  }

  object BotConfig {
    implicit val botConfigEncoder: Encoder[BotConfig] = deriveEncoder[BotConfig]
    implicit val botConfigDecoder: Decoder[BotConfig] = deriveDecoder[BotConfig]
  }

  case class StaticBotsConfig(enabled: Seq[String], configs: Map[String, BotConfig]) {
    def enabledConfigs: Map[String, BotConfig] = configs.filterKeys(enabled contains _)
  }

  final case class DataSourceConfig(`class`: String, topics: Option[Seq[String]], datatypes: Option[Seq[String]])

//  implicit val configEncoder: Encoder[FlashbotConfig] = deriveEncoder[FlashbotConfig]
//  implicit val configDecoder: Decoder[FlashbotConfig] = deriveDecoder[FlashbotConfig]

//  def load(config: Config): Either[Error, FlashbotConfig] =
//    config.as[FlashbotConfig].map(c => c.copy(akka = ))

  def tryLoad: Try[FlashbotConfig] = {
    val overrides = ConfigFactory.defaultOverrides()
    val apps = ConfigFactory.parseResources("application.conf")
    val refs = ConfigFactory.parseResources("reference.conf")

    val conf = overrides
      // Initial fallback is to `application.conf`
      .withFallback(apps)
      // We want `flashbot.akka` reference to override `akka` reference.
      .withFallback(refs.getConfig("flashbot").withOnlyPath("akka"))
      // Then we fallback to default references.
      .withFallback(refs)
      // Finally, resolve.
      .resolve()

    conf.getConfig("flashbot").as[FlashbotConfig]
      .map(c => c.copy(akka = conf)).toTry
  }
  def load: FlashbotConfig = tryLoad.get
}
