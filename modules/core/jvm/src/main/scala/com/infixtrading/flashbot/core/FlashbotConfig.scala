package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, ExchangeConfig, IngestConfig, StaticBotsConfig}
import com.infixtrading.flashbot.models.core.Position
import com.typesafe.config.{Config, ConfigFactory, ConfigObject, ConfigRenderOptions}
import io.circe._
import io.circe.parser._
import io.circe.generic.semiauto._
import pureconfig.ConfigReader
import pureconfig.error.{CannotParse, ConfigReaderFailures, ConvertFailure, ThrowableFailure}
import pureconfig.generic.auto._
import pureconfig.generic.semiauto._

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

case class FlashbotConfig(engineRoot: String,
                          ingest: IngestConfig,
                          strategies: Map[String, String],
                          exchanges: Map[String, ExchangeConfig],
                          sources: Map[String, DataSourceConfig],
                          bots: StaticBotsConfig,
                          akka: Config,
                          db: Config) {
  def noIngest = copy(ingest = ingest.copy(paths = Seq.empty))
}

object FlashbotConfig {

  case class IngestConfig(paths: Seq[String], retention: String)

  case class ExchangeConfig(`class`: String, params: Option[Json], pairs: Option[Seq[String]])

  implicit val pe: Encoder[Position] = Position.postionEn
  implicit val pd: Decoder[Position] = Position.postionDe

  // Required for deriving json encoders and decoders for anything like BotConfig
  // that includes Duration.
  import com.infixtrading.flashbot.util.time._

  implicit val jsonConfigReader: ConfigReader[Json] = ConfigReader.fromFunction[Json] {
    case v: ConfigObject =>
      parse(v.render(ConfigRenderOptions.defaults().setJson(true)))
        .left.map((f: ParsingFailure) => ConfigReaderFailures(ThrowableFailure(f, None)))
  }

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

//    implicit val botConfigReader: ConfigReader[BotConfig] =
//      ConfigReader.forProduct6("strategy", "mode", "params")

    implicit val botConfigReader: ConfigReader[BotConfig] = deriveReader[BotConfig]

  }

  case class StaticBotsConfig(enabled: Seq[String], configs: Map[String, BotConfig]) {
    def enabledConfigs: Map[String, BotConfig] = configs.filterKeys(enabled contains _)
  }

  final case class DataSourceConfig(`class`: String, topics: Option[Seq[String]], datatypes: Option[Seq[String]])

//  implicit val configEncoder: Encoder[FlashbotConfig] = deriveEncoder[FlashbotConfig]
//  implicit val configDecoder: Decoder[FlashbotConfig] = deriveDecoder[FlashbotConfig]

//  def load(config: Config): Either[Error, FlashbotConfig] =
//    config.as[FlashbotConfig].map(c => c.copy(akka = ))

  def tryLoad = {
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

    pureconfig.loadConfig[FlashbotConfig](conf.getConfig("flashbot"))
      .right.map(_.copy(akka = conf))
  }

  def load: FlashbotConfig = tryLoad match {
    case Right(value) => value
    case Left(err) =>
      for (failure <- err.toList) {
        println(failure)
      }
      throw new RuntimeException("Config parsing failure")
  }
}
