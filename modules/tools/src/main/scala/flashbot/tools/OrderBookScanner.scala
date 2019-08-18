package flashbot.tools

import java.awt.{BasicStroke, Color, Paint, Stroke}
import java.time.Instant

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.{ActorAttributes, ActorMaterializer}
import akka.util.Timeout
import flashbot.core.DataType.LadderType
import flashbot.core.{FlashbotConfig, OrderBookTap, TimeSeriesTap}
import flashbot.sources.BitMEXMarketDataSource
import flashbot.util.stream.buildMaterializer
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.stream.scaladsl.{Keep, Sink, Source, Unzip, UnzipWith, UnzipWithApply}
import flashbot.models.{Ladder, OrderBook, TimeRange}
import flashbot.util.TableUtil
import de.sciss.chart.api._
import org.jfree.data.time.{RegularTimePeriod, Second, TimePeriod, TimePeriodValue}

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}
import language.implicitConversions
import java.util.Date

import de.sciss.chart.XYChart
import flashbot.core.TimeSeriesTap.prices
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue
import org.jfree.chart.StandardChartTheme
import org.jfree.chart.axis.{DateAxis, NumberAxis}
import org.jfree.chart.plot.{CombinedDomainXYPlot, DefaultDrawingSupplier, Plot, PlotOrientation, XYPlot}
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer

import scala.collection.mutable.ArrayBuffer


object OrderBookScanner extends App {

  val START = Instant.now()
  val END = START.plusSeconds(60 * 60 * 24)
//  val END = START.plusSeconds(60)

  implicit def timePeriod(d: Date): RegularTimePeriod = new Second(d)

//  implicit val config: FlashbotConfig = FlashbotConfig.load()
//  implicit val system: ActorSystem = ActorSystem(config.systemName, config.conf)
//  implicit val mat: ActorMaterializer = buildMaterializer()
//  implicit val ec: ExecutionContextExecutor = system.dispatcher
//  implicit val timeout: Timeout = Timeout(10 seconds)

  val simPrices = ArrayBuffer.empty[(Date, Double)]
  simPrices.toTimePeriodValues()
  val prices = simPrices.toTimePeriodValuesCollection("Close Prices")

//  simPrices.toTimePeriodValuesCollection("Close Prices").getSeries(0)

  val plot: CombinedDomainXYPlot = new CombinedDomainXYPlot(new DateAxis())
  val rangeAxis = new NumberAxis()
  rangeAxis.setAutoRangeIncludesZero(false)
  val renderer = new XYLineAndShapeRenderer(true, false)
  val simulatedPricePlot = new XYPlot(prices, null, rangeAxis, renderer)
  plot.add(simulatedPricePlot);

  val theme = StandardChartTheme.createDarknessTheme().asInstanceOf[StandardChartTheme]
  val drawingSupplier = new DefaultDrawingSupplier(
    Array[Paint](
      Color.decode("0xFFFF00"), Color.decode("0x0036CC"), Color.decode("0xFF0000"),
      Color.decode("0xFFFF7F"), Color.decode("0x6681CC"), Color.decode("0xFF7F7F"),
      Color.decode("0xFFFFBF"), Color.decode("0x99A6CC"), Color.decode("0xFFBFBF"),
      Color.decode("0xA9A938"), Color.decode("0x2D4587")
    ),
    Array[Paint](Color.decode("0xFFFF00"), Color.decode("0x0036CC")),
    Array[Stroke](new BasicStroke(.5f)),
    Array[Stroke](new BasicStroke(.5f)),
    DefaultDrawingSupplier.DEFAULT_SHAPE_SEQUENCE
  )
  theme.setDrawingSupplier(drawingSupplier)
  val chart = XYChart(plot, "Simulated prices", true)(theme)
//  chart.peer.setAntiAlias(true)
//  chart.peer.setTextAntiAlias(true)
  chart.show("Prices", (1100, 600), true)

  var lastTradeSeqId = 0L
  var closePrice = 0D
  val nowMillis = System.currentTimeMillis()

  // We'll consider every iteration of the book to be one millisecond.
  // Take (END - START) millis items from the iterator.
  var i = -1L
  OrderBookTap
    .simpleLadderSimulation()
    .take(END.toEpochMilli.toInt - START.toEpochMilli.toInt)
    .foreach { ladder =>
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
      }
    }

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
