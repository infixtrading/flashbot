package com.infixtrading.flashbot.engine

import java.io.File
import java.security.InvalidParameterException
import java.time.Instant
import java.util.{Date, TimeZone}

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.infixtrading.flashbot.core.{BalancePoint, FlashbotConfig, Paper}
import com.infixtrading.flashbot.util.files
import com.infixtrading.flashbot.models.api._
import com.infixtrading.flashbot.models.core._
import com.infixtrading.flashbot.report.Report
import com.typesafe.config.ConfigFactory
import de.sciss.chart.api._
import io.circe.Printer
import io.circe.literal._
import io.circe.syntax._
import org.jfree.chart.ChartFactory
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.renderer.xy.{CandlestickRenderer, StandardXYBarPainter, XYBarRenderer}
import org.jfree.data.statistics.{HistogramDataset, HistogramType}
import org.jfree.data.time._
import org.jfree.data.time.ohlc.{OHLCSeries, OHLCSeriesCollection}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import strategies.LookaheadParams

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class TradingEngineSpec
  extends TestKit(ActorSystem("TradingEngineSpec",
    config = {
      val conf = ConfigFactory.load(classOf[TradingEngine].getClassLoader)
      val fbConf = conf.getConfig("flashbot")
      val finalConf =
        ConfigFactory.defaultApplication()
          .withFallback(fbConf)
          .withFallback(conf)
      finalConf
    }
  )) with WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender {

  override def afterAll: Unit = {
    val dataDir = new File(FlashbotConfig.load.`data-root`)
    files.rmRf(dataDir)
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  var testFolder: File = _
  implicit val timeout = Timeout(15 seconds)

  "TradingEngine" should {
    "respond to a ping" in {

      val fbConfig = FlashbotConfig.load

      val dataServer = system.actorOf(Props(new DataServer(
        testFolder,
        fbConfig.sources,
        fbConfig.exchanges,
        None,
        useCluster = false
      )))

      val engine = system.actorOf(Props(new TradingEngine(
        "test",
        fbConfig.strategies,
        fbConfig.exchanges,
        fbConfig.bots.configs,
        Left(dataServer)
      )))

      val result = Await.result(engine ? Ping, 5 seconds)
      result match {
        case Pong(micros) =>
          println(Instant.ofEpochMilli(micros/1000))
        case _ =>
          fail("should respond with a Pong")
      }
    }

    "respect bot TTL" in {
      val engine = system.actorOf(TradingEngine.props("test-engine"))
      def request(query: Any) = Await.result(engine ? query, 5 seconds)

      // Configure bot with 2 second TTL
      Await.result(engine ? ConfigureBot(
        "mybot",
        "candlescanner",
        "{}",
        Paper(0 seconds),
        Some(2 seconds),
        Portfolio.empty
      ), 5 seconds)


      // Status should be Disabled
      request(BotStatusQuery("mybot")) shouldBe Disabled

      // Enable bot
      request(EnableBot("mybot")) shouldBe Done

      // Wait 1 second
      Thread.sleep(1000)

      // Status should be running
      request(BotStatusQuery("mybot")) shouldBe Running

      // Send a heartbeat
      request(BotHeartbeat("mybot")) shouldBe Done

      // Status should still be running 1.5 seconds after heartbeat
      Thread.sleep(1500)
      request(BotStatusQuery("mybot")) shouldBe Running

      // Wait 1 more second
      Thread.sleep(1000)

      // Status should fail with "unknown bot"
      assertThrows[InvalidParameterException] {
        request(BotStatusQuery("mybot"))
      }
    }

    "be profitable when using lookahead" in {
      val fbConfig = FlashbotConfig.load

      val now = Instant.now()

      val dataServer = system.actorOf(Props(
        new DataServer(testFolder, fbConfig.sources, fbConfig.exchanges, None,
          useCluster = false)), "data-server")

      val engine = system.actorOf(Props(
        new TradingEngine("test2", fbConfig.strategies, fbConfig.exchanges, Map.empty,
          Left(dataServer))), "trading-engine-2")

      val params = LookaheadParams(Market("bitfinex/eth_usd"), sabotage = false)

      val report = Await.result((engine ? BacktestQuery(
        "lookahead",
        params.asJson.pretty(Printer.noSpaces),
        TimeRange.build(now, 1 hour),
        Portfolio(
          Map(Account("bitfinex/eth") -> 0, Account("bitfinex/usd") -> 800),
          Map.empty
        ).asJson.pretty(Printer.noSpaces),
        Some(1 minute),
        None
      )).map {
        case ReportResponse(report: Report) => report
      }, timeout.duration)

      report.error shouldBe None

      println("Collections: ", report.collections.keySet)
      println("Time series: ", report.timeSeries.keySet)

      def reportTimePeriod(report: Report): Class[_ <: RegularTimePeriod] =
        (report.barSize.length, report.barSize.unit) match {
          case (1, MILLISECONDS) => classOf[Millisecond]
          case (1, SECONDS) => classOf[Second]
          case (1, MINUTES) => classOf[Minute]
          case (1, HOURS) => classOf[Hour]
          case (1, DAYS) => classOf[Day]
        }

      def buildCandleSeries(report: Report, key: String): OHLCSeries = {
        val priceSeries = new OHLCSeries(key)
        val timeClass = reportTimePeriod(report)
        report.timeSeries(key).foreach { candle =>
          val time =  RegularTimePeriod.createInstance(timeClass,
            new Date(candle.micros / 1000), TimeZone.getDefault)
//          println("adding", timeClass, time, candle)
          priceSeries.add(time, candle.open, candle.high, candle.low, candle.close)
        }
        priceSeries
      }

      val equityCollection = new TimeSeriesCollection()

      val priceCollection = new OHLCSeriesCollection()
//      priceCollection.addSeries(buildCandleSeries(report, "local.equity_usd"))
//      priceCollection.addSeries(buildCandleSeries(report, "local.eth"))

      val chart = ChartFactory.createCandlestickChart("Look-ahead Report", "Time",
        "Price", priceCollection, true)

      val renderer = new CandlestickRenderer
      renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_SMALLEST)

      val plot = chart.getXYPlot
      plot.setRenderer(renderer)


      val histogramData = new HistogramDataset()
      histogramData.setType(HistogramType.RELATIVE_FREQUENCY)

      val returns = report.collections("fill_size").map(_.as[Double].right.get)
      histogramData.addSeries("Fill Size", returns.toArray, 40)

      val histogram = ChartFactory.createHistogram("Fill Size", "Size", "# of trades",
        histogramData, PlotOrientation.VERTICAL, true, true, false)

      val hPlot = histogram.getXYPlot
      hPlot.setForegroundAlpha(0.85f)
      val yaxis = hPlot.getRangeAxis
      yaxis.setAutoTickUnitSelection(true)
      val xyRenderer = hPlot.getRenderer.asInstanceOf[XYBarRenderer]
      xyRenderer.setDrawBarOutline(false)
      xyRenderer.setBarPainter(new StandardXYBarPainter)
      xyRenderer.setShadowVisible(false)

//      chart.setAntiAlias(true)

//      val panel = new ChartPanel(chart)
//      panel.setVisible(true)
//      panel.setFillZoomRectangle(true)
//      panel.setPreferredSize(new java.awt.Dimension(900, 600))

//      val frame = new ChartFrame("Returns", chart)
//      frame.pack()
//      frame.setVisible(true)
//      frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE)


      val mydata = for {
        bp <- report.collections("all/equity").map(_.as[BalancePoint].right.get)
      } yield (bp.micros, bp.balance)

      val priceData =
        for(price <- report.timeSeries("local.bitfinex.eth_usd").dropRight(1))
        yield (price.micros / 1000, price.close)

      val mychart = XYLineChart(mydata)

//      mychart.show("Equity")

//      val fut = Future {
//        Thread.sleep((2 days).toMillis)
//      }
//
//      Await.ready(fut, 5 minutes)

//      val plot = chart.getPlot.asInstanceOf[XYPlot]
//
//      plot.setDomainPannable(true)
//
//      val yAxis = plot.getRangeAxis.asInstanceOf[NumberAxis]
//      yAxis.setForceZeroInRange(false)
//      yAxis.setAutoRanging(true)


//      report.timeSeries("returns").size shouldBe timeSeriesBarCount

      // There shouldn't be a single period of negative returns when the algo is cheating.
    }

//    "lose money when using lookahead to self sabatoge" in {
//
//    }
  }
//
//  "TradingEngine" should "start a bot" in {
//    val system = ActorSystem("test")
//
//    val dataServer = system.actorOf(Props(
//      new DataServer(testFolder, Map.empty, Map.empty, None, useCluster = false)))
//
//    val engine = system.actorOf(Props(
//      new TradingEngine("test", Map.empty, Map.empty, Map.empty, dataServer)))
//
////    (engine ? )
//
//    system.terminate()
//
//  }
//
//  "TradingEngine" should "recover bots after a restart" in {
//  }
//
//  override def withFixture(test: NoArgTest) = {
//    val tempFolder = System.getProperty("java.io.tmpdir")
//    var folder: File = null
//    do {
//      folder = new File(tempFolder, "scalatest-" + System.nanoTime)
//    } while (! folder.mkdir())
//    testFolder = folder
//    try {
//      super.withFixture(test)
//    } finally {
//      rmRf(testFolder)
//    }
//  }
}
