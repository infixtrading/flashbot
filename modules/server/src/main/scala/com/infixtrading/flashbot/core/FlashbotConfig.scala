package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.FlashbotConfig.{StaticBotsConfig, DataSourceConfig, ExchangeConfig, IngestConfig}
import com.infixtrading.flashbot.models.core.Position
import com.typesafe.config.{Config, ConfigFactory, ConfigValue}
import io.circe._
import io.circe.config.syntax._
import io.circe.generic.semiauto._
import io.circe.generic.auto._

import scala.concurrent.duration.Duration
import scala.util.Try

case class FlashbotConfig(`engine-root`: String,
                          ingest: IngestConfig,
                          strategies: Map[String, String],
                          exchanges: Map[String, ExchangeConfig],
                          sources: Map[String, DataSourceConfig],
                          bots: StaticBotsConfig,
                          akka: Config,
                          db: Config)

object FlashbotConfig {

  case class IngestConfig(paths: Seq[String], retention: String)

  case class ExchangeConfig(`class`: String, params: Option[Json], pairs: Option[Seq[String]])

  implicit val pe: Encoder[Position] = Position.postionEn
  implicit val pd: Decoder[Position] = Position.postionDe

  // Required for deriving json encoders and decoders for anything like BotConfig
  // that includes Duration.
  import com.infixtrading.flashbot.util.time._

  case class BotConfig(strategy: String,
                       mode: TradingSessionMode,
                       params: Option[Json],
                       ttl: Option[Duration],
                       `initial-assets`: Option[Map[String, Double]],
                       `initial-positions`: Option[Map[String, Position]])

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
    val conf = overrides.withFallback(apps).withFallback(refs).resolve()
    conf.getConfig("flashbot").as[FlashbotConfig]
      .map(c => c.copy(akka = conf)).toTry
  }
  def load: FlashbotConfig = tryLoad.get
}
