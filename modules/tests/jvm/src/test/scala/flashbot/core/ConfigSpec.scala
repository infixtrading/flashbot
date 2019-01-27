package flashbot.core

import flashbot.config._
import org.scalatest.{FlatSpec, FunSpec, FunSuite, Matchers}

class ConfigSpec extends FlatSpec with Matchers {
  "FlashbotConfig" should "load the default config" in {
    val config = FlashbotConfig.load()
    config.systemName shouldBe "flashbot-system"
    config.db.getString("profile") shouldBe "slick.jdbc.H2Profile$"
    config.engineRoot shouldBe "target/engines"
    config.conf.getString("akka.actor.provider") shouldBe "akka.cluster.ClusterActorRefProvider"
  }

  "FlashbotConfig" should "load in standalone mode" in {
    val config = FlashbotConfig.loadStandalone()
    config.conf.getString("akka.actor.provider") shouldBe "local"
  }

  "FlashbotConfig" should "load postgres db settings" in {
    val config = FlashbotConfig.load("prod")
    config.db.getString("profile") shouldBe "slick.jdbc.PostgresProfile$"
  }

  "FlashbotConfig" should "allow overwriting Akka options" in {
    val config = FlashbotConfig.load("custom-akka")
    config.conf.getString("akka.loglevel") shouldBe "ERROR"
  }
}
