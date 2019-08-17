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
import org.jfree.data.time.{RegularTimePeriod, Second}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}
import language.implicitConversions
import java.util.Date

import de.sciss.chart.XYChart
import flashbot.core.TimeSeriesTap.prices
import org.jfree.chart.StandardChartTheme
import org.jfree.chart.axis.{DateAxis, NumberAxis}
import org.jfree.chart.plot.{CombinedDomainXYPlot, DefaultDrawingSupplier, Plot, PlotOrientation, XYPlot}
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.data.time.{RegularTimePeriod, Second}


object OrderBookScanner extends App {

//  object Implicits extends Implicits
//
//  trait Implicits {
//  }

  implicit def jdate2jfree(d: Date): RegularTimePeriod = new Second(d)

//  implicit val config: FlashbotConfig = FlashbotConfig.load()
//  implicit val system: ActorSystem = ActorSystem(config.systemName, config.conf)
//  implicit val mat: ActorMaterializer = buildMaterializer()
//  implicit val ec: ExecutionContextExecutor = system.dispatcher
//  implicit val timeout: Timeout = Timeout(10 seconds)

  OrderBookTap
    .simpleLadderSimulation()
//    .withAttributes(ActorAttributes.dispatcher(""))
    .zipWithIndex.filter(_._2 % 50000 == 0).map(_._1)
  //    .take(10000000)
//    .filter(_ => Random.nextInt(100000) == 0)
//    .groupedWithin(100000, 50 millis)
//    .map(_.lastOption)
//    .collect { case Some(x) => x }
    .foreach { ladder =>
      //      Thread.sleep(1)
      TableUtil.renderLadder(ladder, 1000)
    }

    System.exit(0)
//    .runForeach { ladder =>
//      Thread.sleep(1)
//      TableUtil.renderLadder(ladder, 5)
//    }
//    .onComplete {
//      case Success(_) =>
//        println("Done")
//      case Failure(err) =>
//        println("FOOOOOOOOOOOOOOOOOO BAR")
//        println(err)
//        println(err.getStackTrace)
//    }

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
//    val plot: CombinedDomainXYPlot = new CombinedDomainXYPlot(new DateAxis())
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
