package flashbot.strategies

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import io.circe.syntax._
import flashbot.client.FlashbotClient
import flashbot.core.MarketData.BaseMarketData
import flashbot.core._
import flashbot.models.api.DataOverride
import flashbot.models.core.{Candle, Portfolio, TimeRange}

import scala.concurrent.duration._
import org.scalatest.{FlatSpec, Matchers}
import util.TestDB

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

class MarketMakerSpec extends FlatSpec with Matchers {

  "MarketMaker" should "be profitable in a sideways market" in {
    val timeRange = TimeRange.build(Instant.now, "2d")
    val candles: Source[MarketData[Candle], NotUsed] = TimeSeriesTap
      .prices(1000, 0, 0.04, timeRange, 10 seconds)
      .via(TimeSeriesTap.aggregateCandles(1 minute))
      .zipWithIndex
      .map { case (c, i) => BaseMarketData(c, "coinbase/btc_usd/candles_1m", c.micros, 1, i) }

    implicit val config = FlashbotConfig.load()
    implicit val system = ActorSystem(config.systemName, config.conf)
    implicit val mat = ActorMaterializer()
    val engine = system.actorOf(TradingEngine.props("market-maker", config))
    val client = new FlashbotClient(engine)

    val params = MarketMakerParams("coinbase/btc_usd", "candles_1m", "sma7", 10, .1, .2)
    val portfolio = Portfolio.empty
      .withAssetBalance("coinbase/btc", 5.0)
      .withAssetBalance("coinbase/usd", 2000)

    val data = Seq(DataOverride("coinbase/btc_usd/candles_1m", candles))

    val report = client.backtest("market_maker", params.asJson, portfolio, 1 minute, timeRange, data)

    println(report.timeSeries.keySet)

    val prices = report.timeSeries("local.price.coinbase.btc_usd").map(_.close)
    val fairPrices = report.timeSeries("local.indicator.fair_price_sma7").map(_.close)
    val equity = report.timeSeries("local.indicator.equity").map(_.close)
    (prices zip fairPrices zip equity).foreach(println)

    println(report.trades)

    Await.ready(for {
      _ <- system.terminate()
      _ <- TestDB.dropTestDB()
    } yield Unit, 10 seconds)
  }
}
