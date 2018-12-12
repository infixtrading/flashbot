package com.infixtrading.flashbot.core

import com.infixtrading.flashbot.core.FlashbotConfig.{BotConfigJson, DataSourceConfig, ExchangeConfig, IngestConfig}
import com.infixtrading.flashbot.models.core.Position
import com.typesafe.config.Config
import io.circe._
import io.circe.config.syntax._
import io.circe.generic.auto._

case class FlashbotConfig(`api-key`: Option[String],
                           `data-root`: String,
                          `market-data-root`: String,
                          `app-data-root`: String,
                          ingest: IngestConfig,
                          strategies: Map[String, String],
                          exchanges: Map[String, ExchangeConfig],
                          sources: Map[String, DataSourceConfig],
                          bots: BotConfigJson)

object FlashbotConfig {

  case class IngestConfig(paths: Seq[String], retention: String)

  case class ExchangeConfig(`class`: String, params: Option[Json], pairs: Option[Seq[String]])

  implicit val pe: Encoder[Position] = Position.postionEn
  implicit val pd: Decoder[Position] = Position.postionDe

  case class BotConfig(strategy: String,
                       mode: String,
                       params: Option[Json],
                       `initial-assets`: Option[Map[String, Double]],
                       `initial-positions`: Option[Map[String, Position]])

  case class BotConfigJson(default: Seq[String], configs: Map[String, BotConfig])

  final case class DataSourceConfig(`class`: String, topics: Option[Seq[String]], datatypes: Option[Seq[String]])

//  implicit val configEncoder: Encoder[FlashbotConfig] = deriveEncoder[FlashbotConfig]
//  implicit val configDecoder: Decoder[FlashbotConfig] = deriveDecoder[FlashbotConfig]

  def load(config: Config): Either[Error, FlashbotConfig] = config.as[FlashbotConfig]
}
