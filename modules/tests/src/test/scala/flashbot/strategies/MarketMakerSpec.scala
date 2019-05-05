package flashbot.strategies

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.{Sink, Source}
import io.circe.syntax._
import flashbot.client.FlashbotClient
import flashbot.core.MarketData.BaseMarketData
import flashbot.core._
import flashbot.models.{Candle, DataOverride, Order, Portfolio, Position, TimeRange}

import scala.concurrent.duration._
import org.scalatest.{FlatSpec, Matchers}
import util.TestDB

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps

class MarketMakerSpec extends FlatSpec with Matchers {

  "MarketMaker" should "work with hard coded candles" in {
    val startTime = Instant.EPOCH
    def candleMicros(i: Int) = startTime.plus(i, ChronoUnit.MINUTES).toEpochMilli * 1000
    val candles: Source[MarketData[Candle], NotUsed] = Source(List(
      // fair price: 99.00
      // places 20 quotes: 99.50 - 104.00, and 98.50 - 94.00.
      Candle(candleMicros(0), 100, 101, 98, 99, 0),
      // fair price: 100.50
      // fills all 10 asks. submits 20 more quotes:
      // asks: 101.00 - 105.50. bids: 100.00 - 95.50
      Candle(candleMicros(1), 103, 105, 101, 102, 0),
      // fair price: 101.00
      // fills 4 asks and no bids. submits 20 more quotes:
      // asks: 101.50 - 107.00. bids: 100.50 - 96.00
      Candle(candleMicros(2), 102, 104, 100, 102, 0),
      Candle(candleMicros(3), 100, 101, 96, 97, 0),
      Candle(candleMicros(4), 96, 102, 96, 99, 0),
      Candle(candleMicros(5), 99, 99, 91, 96, 0),
      Candle(candleMicros(6), 96, 105, 95, 99, 0),

      Candle(candleMicros(7), 100, 101, 98, 99, 0),
      Candle(candleMicros(8), 103, 105, 101, 102, 0),
      Candle(candleMicros(9), 102, 106, 100, 103, 0),
      Candle(candleMicros(10), 100, 101, 96, 97, 0),
      Candle(candleMicros(11), 96, 112, 96, 112, 0),
      Candle(candleMicros(12), 112, 112, 91, 96, 0),
      Candle(candleMicros(13), 96, 105, 95, 99, 0),
    )).zipWithIndex.map { case (c, i) => BaseMarketData(c, "coinbase/btc_usd/candles_1m", c.micros, 1, i) }

    val timeRange = TimeRange(0)

    implicit val config = FlashbotConfig.load()
    implicit val system = ActorSystem(config.systemName, config.conf)
    implicit val mat = ActorMaterializer()

    val engine = system.actorOf(TradingEngine.props("market-maker", config))
    val client = new FlashbotClient(engine)

    val params = MarketMakerParams("coinbase/btc_usd", "candles_1m", "sma", 4, 10, .5, .2, 4, 2.0)
    val portfolio = Portfolio.empty
      .withBalance("coinbase.btc", 5.0)
      .withBalance("coinbase.usd", 2000)
    val data = Seq(DataOverride("coinbase/btc_usd/candles_1m", candles))

    val report = client.backtest("market_maker", params.asJson, portfolio.toString, 1 minute, timeRange, data)
    val prices = report.timeSeries("coinbase.btc_usd").close.toVector()
    val fairPrices = report.timeSeries("fair_price_sma").close.toVector()
    val equity = report.timeSeries("equity").close.toVector()
    (prices zip fairPrices zip equity).foreach(println)

    report.trades.len shouldEqual 103

    Await.ready(for {
      _ <- system.terminate()
      _ <- TestDB.dropTestDB()
    } yield Unit, 10 seconds)
  }

  "MarketMaker" should "be profitable in a sideways market" in {

    implicit val config = FlashbotConfig.load()
    implicit val system = ActorSystem(config.systemName, config.conf)
    implicit val mat = ActorMaterializer()

    val engine = system.actorOf(TradingEngine.props("market-maker", config))
    val client = new FlashbotClient(engine)

    // Estimate mean reverting time series with a sin wave.
    // Price range is $90 to $110.
    // Each point represents 10 seconds. 6 hours in 360 minutes.
    // Use hourly candles and SMA_6 fair price.
    // 6 * 50 hours in time range.
    val candles = Source((0 to (360 * 6 * 50)).map(i =>
        (Instant.EPOCH.plusSeconds(i * 20), math.sin(math.toRadians(i)) * 10 + 100)))
      .via(TimeSeriesTap.aggregatePrices(1 hour))
      .zipWithIndex
      .map { case (c, i) => BaseMarketData(c, "coinbase/btc_usd/candles_1h", c.micros, 1, i) }

    val candleSeq = Await.result(candles.runWith(Sink.seq), 1 second)
    val timeRange = TimeRange(candleSeq.head.micros, candleSeq.last.micros)
    val params = MarketMakerParams("coinbase/btc_usd", "candles_1h", "sma", 6, 10, 1, 1, 4, 2.0)
    val portfolio = Portfolio.empty
      .withBalance("coinbase/btc", 20.0)
      .withBalance("coinbase/usd", 2000)
    val data = Seq(DataOverride("coinbase/btc_usd/candles_1h", candles))

    val report = client.backtest("market_maker", params.asJson, portfolio.toString, 1 hour, timeRange, data)
    val prices = report.timeSeries("coinbase.btc_usd").close.toVector
    val fairPrices = report.timeSeries("fair_price_sma").close.toVector
    val equity = report.timeSeries("equity").close.toVector
    (prices zip fairPrices zip equity).foreach(println)

    println("# trades: ", report.trades.len)

    equity.last > 4000 shouldBe true

    Await.ready(for {
      _ <- system.terminate()
      _ <- TestDB.dropTestDB()
    } yield Unit, 10 seconds)
  }

  "MarketMaker" should "have the expected portfolio in an increasing market" in {

    implicit val config = FlashbotConfig.load()
    implicit val system = ActorSystem(config.systemName, config.conf)
    implicit val mat = ActorMaterializer()

    val engine = system.actorOf(TradingEngine.props("market-maker", config))
    val client = new FlashbotClient(engine)

    val trades = (0 to 30).map(i => {
      val micros = i * 1000000 * 20 // Trade every 20 seconds
      val trade = Trade(i.toString, micros, 4000 + i, .01, Order.Up)
      BaseMarketData(trade, "coinbase/btc_usd/trades", micros, 0, i)
    })
    val timeRange = TimeRange(trades.head.micros, trades.last.micros)
    val params = MarketMakerParams("coinbase/btc_usd", "trades", "sma", 2, 2, 1, .1, 4, 2.0)
    val portfolio = Portfolio.empty
      .withBalance("coinbase/btc", 1.05)
      .withBalance("coinbase/usd", 1000)
      .withPosition("bitmex/xbtusd", new Position((-1.05 * 4000).toLong, 2, java.lang.Double.NaN))
    val data = Seq(DataOverride("coinbase/btc_usd/trades", Source(trades)))

    val report = client.backtest("market_maker",
      params.asJson, portfolio.toString, 1 minute, timeRange, data)

    val prices = report.timeSeries("coinbase.btc_usd").close.toVector
    val fairPrices = report.timeSeries("fair_price_sma").close.toVector
    val equity = report.timeSeries("equity").close.toVector
    val usd = report.timeSeries("cash").close.toVector
    val btc = report.timeSeries("position").close.toVector
    val hedge = report.timeSeries("hedge").close.toVector

    def round(d: Double): Double = BigDecimal.valueOf(d)
      .setScale(2, BigDecimal.RoundingMode.HALF_UP).doubleValue()

    def pairsToRow(pairs: Any): String = pairs match {
      case d: Double => round(d).toString
      case (x: Any, y: Any) => pairsToRow(x) + "\t" + pairsToRow(y)
    }

    (prices zip fairPrices zip equity zip usd zip btc zip hedge).map(pairsToRow).foreach(println)

    round(btc.last) shouldBe .05

    Await.ready(for {
      _ <- system.terminate()
      _ <- TestDB.dropTestDB()
    } yield Unit, 10 seconds)
  }
}
