package com.infixtrading.flashbot.core

import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{FlatSpec, FunSpec, FunSuite, Matchers}

class ConfigTest extends FlatSpec with Matchers {
  "FlashbotConfig" should "load the default config" in {
    FlashbotConfig.load(ConfigFactory.load.getConfig("flashbot"))
      .right.get.exchanges("bitstamp")
      .`class`shouldEqual "com.infixtrading.flashbot.exchanges.Bitstamp"
  }
}
