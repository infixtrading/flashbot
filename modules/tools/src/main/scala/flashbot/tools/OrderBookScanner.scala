package flashbot.tools

import java.awt.event.{KeyEvent, KeyListener}
import java.awt.{BasicStroke, Color, Dimension, Paint, Stroke}
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.time.temporal.ChronoUnit

import flashbot.core.{CandleFrame, DataServer, FlashbotConfig, OrderBookTap, PriceTap, TradingEngine}
import flashbot.sources.BitMEXMarketDataSource
import flashbot.util.stream.buildMaterializer
import flashbot.util.timeseries.Implicits._
import flashbot.util.time._
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.stream.scaladsl.{Keep, Sink, Source, Unzip, UnzipWith, UnzipWithApply}
import flashbot.models.{Candle, Ladder, OrderBook, TimeRange}
import flashbot.util.TableUtil
import de.sciss.chart.api._
import org.jfree.data.time.{RegularTimePeriod, Second, TimePeriod, TimePeriodValue, TimePeriodValuesCollection}

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}
import language.implicitConversions
import java.util.Date

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.sciss.chart.XYChart
import de.sciss.chart.event.{ChartMouseClicked, ChartMouseMoved}
import flashbot.client.FlashbotClient
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue
import javax.swing.JFrame
import org.jfree.chart.{ChartFrame, ChartMouseEvent, ChartMouseListener, ChartPanel, StandardChartTheme}
import org.jfree.chart.axis.{AxisLocation, AxisSpace, DateAxis, NumberAxis}
import org.jfree.chart.event.{AxisChangeEvent, ChartChangeEvent, ChartChangeListener, PlotChangeEvent, PlotChangeListener}
import org.jfree.chart.plot.{CombinedDomainXYPlot, DefaultDrawingSupplier, Plot, PlotOrientation, XYPlot}
import org.jfree.chart.renderer.xy.{XYBarRenderer, XYLineAndShapeRenderer}
import org.ta4j.core.{BaseTimeSeries, num}

import scala.collection.mutable.ArrayBuffer
import scala.swing.{Frame, Publisher}
import scala.swing.event.{MouseClicked, MouseMoved}


object OrderBookScanner extends App {

  val END = Instant.now()
  val START = END.minus(7, ChronoUnit.DAYS)
  val timeRange = TimeRange(START.toEpochMilli * 1000, END.toEpochMilli * 1000)

  implicit def timePeriod(d: Date): RegularTimePeriod = new Second(d)
  implicit def timePeriod(d: Instant): RegularTimePeriod = new Second(new Date(d.toEpochMilli))

  // Setup Flashbot environment
  implicit val config: FlashbotConfig = FlashbotConfig.load("scanner-test")
  implicit val system: ActorSystem = ActorSystem(config.systemName, config.conf)
  implicit val mat: ActorMaterializer = buildMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit val timeout: Timeout = Timeout(10 seconds)

  val engine = system.actorOf(TradingEngine.props("scanner-test-engine", config))

  val client = new FlashbotClient(engine)
  val cbp: Map[String, CandleFrame] = client.prices("coinbase/btc_usd/candles_1m", timeRange, 1 minute)
  val cb: Array[Candle] = cbp("coinbase.btc_usd").toCandlesArray
  val (coinbasePrices, coinbaseVols) = cb.toSeq.map(x => ((x.date, x.close), (x.date, x.volume))).unzip


  val simPrices = ArrayBuffer.empty[(Date, Double)]
  val refPrices = ArrayBuffer.empty[(Date, Double)]
  val spreadBuffer = ArrayBuffer.empty[(Date, Double)]

//  val prices = simPrices.toTimePeriodValuesCollection("Close Prices")
//  prices.addSeries(refPrices.toTimePeriodValues("Reference prices"))
  val prices = new TimePeriodValuesCollection()
  prices.addSeries(coinbasePrices.toTimePeriodValues("Coinbase prices"))

  val spread = spreadBuffer.toTimePeriodValuesCollection("Spread")
  spread.addSeries(spreadBuffer.toTimePeriodValues("Spread"))

//  simPrices.toTimePeriodValuesCollection("Close Prices").getSeries(0)

  val plot: CombinedDomainXYPlot = new CombinedDomainXYPlot(new DateAxis())
//  plot.setRangePannable(false)

  val rangeAxis = new NumberAxis()
  rangeAxis.setAutoRange(true)
  rangeAxis.setAutoRangeIncludesZero(false)
  rangeAxis.setLowerMargin(.3)
  rangeAxis.configure()

  val volRangeAxis = new NumberAxis()
  volRangeAxis.setUpperMargin(5)
  volRangeAxis.setAutoRange(true)

  val spreadRangeAxis = new NumberAxis()

  val pricePlot = new XYPlot()

  // Axes
  pricePlot.setRangeAxes(List(rangeAxis, volRangeAxis).toArray)

  // Coinbase price
  val cbRenderer = new XYLineAndShapeRenderer(true, false)
  cbRenderer.setPaint(Color.YELLOW)
  cbRenderer.setStroke(new BasicStroke(.5f))
  pricePlot.setDataset(0, ToXYDataset[TimePeriodValues].convert(coinbasePrices.toTimePeriodValues("Coinbase prices")))
  pricePlot.setRenderer(0, cbRenderer)
  pricePlot.mapDatasetToRangeAxis(0, 0)

  // Coinbase volume
  volRangeAxis.setAutoRangeStickyZero(true)
  volRangeAxis.setAutoRangeIncludesZero(true)
  val volRenderer = new XYBarRenderer()
  volRenderer.setPaint(Color.YELLOW)
  pricePlot.setDataset(1, ToXYDataset[TimePeriodValues].convert(coinbaseVols.toTimePeriodValues("Coinbase Volume")))
  pricePlot.setRenderer(1, volRenderer)
  pricePlot.setRangeAxis(1, volRangeAxis)
  pricePlot.setRangeAxisLocation(1, AxisLocation.TOP_OR_RIGHT)
  pricePlot.mapDatasetToRangeAxis(1, 1)

  // Brownian
  val brownianPrices = PriceTap.iterator(10000, .00005, .08, timeRange, 1 minute).toSeq
  prices.addSeries(brownianPrices.toTimePeriodValues("Simulated brownian prices"))
  val refPricesIt = brownianPrices.iterator
  val brownianrenderer = new XYLineAndShapeRenderer(true, false)
  brownianrenderer.setStroke(new BasicStroke(.5f))
  brownianrenderer.setPaint(Color.WHITE)
  pricePlot.setDataset(2, ToXYDataset[TimePeriodValues].convert(brownianPrices.toTimePeriodValues("Brownian prices")))
  pricePlot.setRenderer(2, brownianrenderer)
  pricePlot.mapDatasetToRangeAxis(2, 0)

  // Order book
//  val orderBookPrices = OrderBookTap.simpleLadderSimulation()

//  val spreadPlot = new XYPlot(spread, null, spreadRangeAxis, renderer)

  plot.add(pricePlot, 5)

  lazy val coinbaseSeries = "coinbase/btc_usd".timeSeries.withInterval(5 minutes)

  lazy val _cbTimeSeries = new BaseTimeSeries.SeriesBuilder()
    .withName("coinbase/btc_usd")
    .withMaxBarCount(10000)
    .withNumTypeOf(num.DoubleNum.valueOf(_))
    .build()
  cb.foreach { candle =>
    val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(candle.millis), ZoneOffset.UTC)
    _cbTimeSeries.addBar(java.time.Duration.ofMillis(60 * 1000), zdt)
  }


  val accelerationIndicatorPlot = new XYPlot()
  plot.add(accelerationIndicatorPlot)

  val theme = StandardChartTheme.createDarknessTheme().asInstanceOf[StandardChartTheme]

  val chart = XYChart(plot, "Simulated prices", true)(theme)
  chart.peer.setAntiAlias(true)
  chart.peer.setTextAntiAlias(true)
//  chart.show("Prices", (1100, 600), true)

  def showFrame(): Unit = {
    class PriceFrame extends Frame with Publisher {
      override lazy val peer = new ChartFrame("Order Book Simulation", chart.peer, true) with InterfaceMixin

      peer.getChartPanel.setRangeZoomable(false)

      var zoomListener: PlotChangeListener = _

      def resetZoomListener(): Unit = {
        zoomListener = new PlotChangeListener {
          override def plotChanged(event: PlotChangeEvent): Unit = {
            println("HELLO", event)
            plot.removeChangeListener(zoomListener)
//            peer.getChartPanel.setRangeZoomable(true)
            plot.getSubplots.forEach { _p =>
              if (_p.isInstanceOf[XYPlot]) {
                val p: XYPlot = _p.asInstanceOf[XYPlot]

                if (p eq pricePlot) println("EQUAL !!!!!!!")

                println("FOOBAR 2")
                println(pricePlot.getRangeAxis(0).getLowerBound,
                  pricePlot.getRenderer.findRangeBounds(pricePlot.getDataset(0)).getLowerBound)
                println(pricePlot.getRangeAxis(0).getUpperBound,
                  pricePlot.getRenderer.findRangeBounds(pricePlot.getDataset(0)).getUpperBound)

                println("configure")

                for (i <- 0 until pricePlot.getRangeAxisCount) {
                  println(i)
                  val axis = pricePlot.getRangeAxis(i)
                  axis.setRangeWithMargins(pricePlot.getDataRange(axis))
                }

                println("FOOBAR 3")

                println(pricePlot.getRangeAxis(0).getLowerBound,
                  pricePlot.getRenderer.findRangeBounds(pricePlot.getDataset(0)).getLowerBound)
                println(pricePlot.getRangeAxis(0).getUpperBound,
                  pricePlot.getRenderer.findRangeBounds(pricePlot.getDataset(0)).getUpperBound)

              }
            }

//            plot.getRenderer.

            resetZoomListener()
          }
        }
        plot.addChangeListener(zoomListener)
      }

      resetZoomListener()

      peer.addKeyListener(new KeyListener {
        override def keyTyped(e: KeyEvent): Unit = {
          // Space
          if (e.getExtendedKeyCode == 32) {
            println("Pause")
          }
        }

        override def keyPressed(e: KeyEvent): Unit = {}

        override def keyReleased(e: KeyEvent): Unit = {}
      })
    }
    val frame = new PriceFrame

//    applyScalaSwingListenerTo(frame.peer.getChartPanel, frame)

    frame.size = new Dimension(1100, 600)
    frame.visible = true
  }

  showFrame()
  Thread.sleep(1000 * 100)

//  frame.add("Chart", chart.peer)

  var lastTradeSeqId = 0L
  var closePrice = 0D
  val nowMillis = System.currentTimeMillis()

  // We'll consider every iteration of the book to be one millisecond.
  // Take (END - START) millis items from the iterator.
  var i = -1L

//  val refPricesFlatMapBuf = new Array[Double](60000)
  val sourceIterator = OrderBookTap.simpleLadderSimulation(refPricesIt, 14 days)

//  val sourceIterator = laddersIt.zip(refPricesIt)
//    .take(END.toEpochMilli.toInt - START.toEpochMilli.toInt)


  sealed trait ChartMode
  case object Live extends ChartMode
  case object Done extends ChartMode
  case object Paused extends ChartMode

  var currentMode: ChartMode = Live

  def stepForward(): Unit = {
    if (!sourceIterator.hasNext) {
      throw new RuntimeException("Cannot step forward on empty iterator")
    }

    sourceIterator.next() match  {
      case (_, ladder, refPrice) =>
        i += 1

        // Keep track of close price
        if (ladder.aggTradeSeqId > lastTradeSeqId) {
          lastTradeSeqId = ladder.aggTradeSeqId
          closePrice = ladder.matchPrices(ladder.matchCount - 1)
        }

        // Add close price into dataset
        if (i > 0 && i % 60000 == 0) {
          prices.getSeries(0).add(
            timePeriod(Date.from(START.plusMillis(i))),
            closePrice
          )

          prices.getSeries(1).add(
            timePeriod(Date.from(START.plusMillis(i))),
            refPrice
          )

          spread.getSeries(0).add(
            timePeriod(Date.from(START.plusMillis(i))),
            refPrice - closePrice
          )
        }

        if (i > 0 && i % 600000 == 0) {
  //        TableUtil.renderLadder(ladder, depthZoom = 1000)
        }
    }
  }

  // Main loop
  while (true) {
    currentMode match {
      case Live =>
        if (sourceIterator.hasNext) stepForward()
        else currentMode = Done

      case Paused =>
        Thread.sleep(50)

      case Done =>
        Thread.sleep(50)
    }
  }

//    .foreach

//  simulatedPricePlot.setDataset(simPrices.toTimePeriodValuesCollection("Close Prices"))

//    System.exit(0)

//  val pp: List[(Date, Double)] = Await.result(TimeSeriesTap
//    .prices(100, .5, .5, TimeRange.build(Instant.now, "now", "24h"), 1 minute)
//    .map((pair: (Instant, Double)) => (new Date(pair._1.toEpochMilli), pair._2))
//    .runWith(Sink.seq), 30 seconds).toList

//  val smaBars: Int = 30
//  for {
//    data <- OrderBookTap(100, .01, .005, .15, smaBars).take(1000).runWith(Sink.seq)
//  } yield {
//    val (dates, series) = data.unzip
//    val (close, sma) = series.unzip
//    val closeData = dates.zip(close)
//    val smaData = dates.zip(sma)
//
//    val rangeAxis = new NumberAxis()
//    val renderer = new XYLineAndShapeRenderer(true, false)
//    plot.add(new XYPlot(closeData.toTimePeriodValuesCollection("Close Prices"), null, rangeAxis, renderer));
//    plot.add(new XYPlot(smaData.toTimePeriodValuesCollection(
//      s"Simple Moving Average ($smaBars)"), null, rangeAxis, renderer));
//
//    val theme = StandardChartTheme.createDarknessTheme().asInstanceOf[StandardChartTheme]
//    val drawingSupplier = new DefaultDrawingSupplier(
//      Array[Paint](
//        Color.decode("0xFFFF00"), Color.decode("0x0036CC"), Color.decode("0xFF0000"),
//        Color.decode("0xFFFF7F"), Color.decode("0x6681CC"), Color.decode("0xFF7F7F"),
//        Color.decode("0xFFFFBF"), Color.decode("0x99A6CC"), Color.decode("0xFFBFBF"),
//        Color.decode("0xA9A938"), Color.decode("0x2D4587")
//      ),
//      Array[Paint](Color.decode("0xFFFF00"), Color.decode("0x0036CC")),
//      Array[Stroke](new BasicStroke(.5f)),
//      Array[Stroke](new BasicStroke(.5f)),
//      DefaultDrawingSupplier.DEFAULT_SHAPE_SEQUENCE
//    )
//    theme.setDrawingSupplier(drawingSupplier)
//    val chart = XYChart(plot, "Simulated prices", true)(theme)
//    chart.peer.setAntiAlias(true)
//    chart.peer.setTextAntiAlias(true)
//    chart.show("Prices", (1100, 600), true)
//  }



//  val series = List((new Date,1)).toTimePeriodValues("series")

//  val ps = TimeSeriesTap.prices
//    .map((pair: (Instant, Double)) => (new Date(pair._1.toEpochMilli), pair._2))
//    .runWith(Sink.seq[(Date, Double)])
//    .onComplete {
//      case Success(value) =>
//        val chart = XYLineChart(value.toList)
//        chart.show("Foobar")
//
//    }


//  val plot = Vegas("Country Pop").
//  withData(
//    Seq(
//      Map("country" -> "USA", "population" -> 314),
//      Map("country" -> "UK", "population" -> 64),
//      Map("country" -> "DK", "population" -> 80)
//    )
//  ).
//  encodeX("country", Nom).
//  encodeY("population", Quant).
//  mark(Bar)
//
//  plot.show


//  val bitmex = new BitMEXMarketDataSource()
//
//  class ScannerActor extends Actor {
//    override def receive: Receive = {
//      case "scan" =>
//        bitmex.ingest("XBTUSD", LadderType(Some(25))) pipeTo sender
//    }
//  }
//
//  val scanner = system.actorOf(Props(new ScannerActor()))
//  val src = Await.result((scanner ? "scan").mapTo[Source[(Long, Ladder), NotUsed]], timeout.duration)
//
////  Source[OrderBook](OrderBookTap(.01, 100).toList)
//
//  src.groupedWithin(500, 50 millis)
//    .map(_.lastOption)
//    .collect {
//      case Some(x) => x
//    } runForeach {
//      case (micros, ladder) =>
//        TableUtil.renderLadder(ladder)
//    }
}
