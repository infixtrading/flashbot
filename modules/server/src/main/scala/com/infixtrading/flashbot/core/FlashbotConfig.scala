package com.infixtrading.flashbot.core

import io.circe._
import io.circe.generic.semiauto._

object FlashbotConfig {

  case class IngestConfig(paths: Seq[String], retention: String)

  case class ExchangeConfig(`class`: String, params: Json, pairs: Seq[String])

  implicit val pe: Encoder[Position] = Position.postionEn
  implicit val pd: Decoder[Position] = Position.postionDe

  case class BotConfig(strategy: String,
                       mode: String,
                       params: Json,
                       initial_assets: Map[String, Double],
                       initial_positions: Map[String, Position])

  def buildBotConfigs: Seq[BotConfig] = Seq.empty

  case class BotConfigJson(default: Seq[String], all: Map[String, BotConfig]) {
    def bots: Map[String, BotConfig] = all
  }

  final case class DataSourceConfig(`class`: String, topics: Seq[String], datatypes: Seq[String])

  case class ConfigFile(dataroot: String,
                        marketdata: String,
                        appdata: String,
                        apikey: Option[String],
                        ingest: IngestConfig,
                        strategies: Map[String, String],
                        exchanges: Map[String, ExchangeConfig],
                        sources: Map[String, DataSourceConfig],
                        bots: BotConfigJson)

  implicit val configEncoder: Encoder[ConfigFile] = deriveEncoder[ConfigFile]
  implicit val configDecoder: Decoder[ConfigFile] = deriveDecoder[ConfigFile]

  def loadConfig(config: Config): ConfigFile = {
    val result = config.as[ConfigFile]
    result.left.foreach(err => {
      println("Got an error")
      println(err)
      println(err.getMessage)
    })
    result.right.get
  }

}
