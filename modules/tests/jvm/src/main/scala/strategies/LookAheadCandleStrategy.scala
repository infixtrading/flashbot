package strategies

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import flashbot.core.DataType.CandlesType
import flashbot.core.Instrument.CurrencyPair
import flashbot.core.MarketData.BaseMarketData
import flashbot.core.{SessionLoader, _}
import flashbot.models.api.{DataOverride, DataSelection, OrderTarget}
import flashbot.models.core._
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._
import io.circe.parser._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

case class Prediction[T](prediction: T, confidence: Double)

trait Predictor1[A1, R] {
  def predict(a1: A1): Prediction[R]
}

trait Predictor2[A1, A2, R] {
  def predict(a1: A1, a2: A2): Prediction[R]
}

trait Predictor3[A1, A2, A3, R] {
  def predict(a1: A1, a2: A2, a3: A3): Prediction[R]
}


case class LookaheadParams(market: Market, sabotage: Boolean)
object LookaheadParams {
  implicit def lookaheadEncoder: Encoder[LookaheadParams] = deriveEncoder
  implicit def lookaheadDecoder: Decoder[LookaheadParams] = deriveDecoder
}

/**
  * A strategy that predicts data one step forwards in time.
  */
class LookAheadCandleStrategy extends Strategy[LookaheadParams]
    with Predictor1[MarketData[Candle], Double]
    with TimeSeriesMixin {

  import FixedSize.numericDouble._

  override def decodeParams(paramsStr: String) = decode[LookaheadParams](paramsStr).toTry

  // Source the data from the strategy itself.
  val path1 = DataPath("bitfinex", "eth_usd", CandlesType(5 seconds))
  def dataSeqs(tr: TimeRange)(implicit mat: Materializer): Map[DataPath[Candle], Seq[MarketData[Candle]]] = Map(
    path1 -> Await.result(
      TimeSeriesTap.prices(100.0, .2, .6, tr, timeStep = 1 minute).zipWithIndex.map {
        case ((instant, price), i) =>
          val micros = instant.toEpochMilli * 1000
          BaseMarketData(Candle(micros, price, price, price, price, 0), path1, micros, 1, i)
      } .toMat(Sink.fold(Seq.empty[MarketData[Candle]]) {
        case (memo, md) => memo :+ md
      })(Keep.right).run(), 15 seconds)
  )

  var staticData = Map.empty[DataPath[Candle], Seq[MarketData[Candle]]]

  /**
   * Human readable title for display purposes.
   */
  override def title = "Look Ahead Strategy"

  /**
    * Example strategy that looks ahead by one variable. The strategy must be defined in terms of
    * confidences of the lookahead prediction.
    */
  override def initialize(portfolio: Portfolio, loader: SessionLoader) = {
    import loader._
    Future {
//      staticData = dataSeqs
      Seq("bitfinex/eth_usd/candles_5s")
    }
  }

  var prediction: Option[Double] = None

  override def handleData(md: MarketData[_])(implicit ctx: TradingSession) = md.data match {
    case candle: Candle =>

      recordCandle((md.source, md.topic), candle)

      if (prediction.isDefined) {
        if (prediction.get != candle.close) {
          println(s"ERROR: Expected prediction ${prediction.get}, got ${candle}")
        } else {
//          println(s"Successful prediction. We predicted ${prediction.series}, and we got $candle")
        }
      }
      // If high confidence prediction, follow it blindly. If low, do nothing.
      val p = predict(md.asInstanceOf[MarketData[Candle]])
      if (p.confidence > .75) {
        prediction = Some(p.prediction)
        val sym = md.path
        val market = Market(md.source, md.topic)
        val pair = CurrencyPair(md.topic)

        def buy() = marketOrder(market,
          ctx.getPortfolio.balance(Account(md.source, pair.quote)).size)

        def sell() = marketOrder(market,
          -ctx.getPortfolio.balance(Account(md.source, pair.base)).size)

        // Price about to go up, buy as much as we can.
        if (prediction.get > candle.close) {
          if (params.sabotage) sell() else buy()
        } else if (prediction.get < candle.close) {
          if (params.sabotage) buy() else sell()
        }
      } else {
        prediction = None
      }
  }

  /**
    * Since our data is static, we can cheat on prediction, making it possible to test expected
    * results on random data.
    */
  override def predict(md: MarketData[Candle]): Prediction[Double] = {
    staticData(md.path)
      .dropWhile(_.micros <= md.micros)
      .headOption.map(_.data.close)
      .map(Prediction(_, 1.0)).getOrElse(Prediction(0, 0))
  }

  /**
    * Make the resolved market data lag by one item. This way we can lookahead to the next
    * item being streamed and base our test strategy off of it.
    */
  override def resolveMarketData[T](selection: DataSelection[T], dataServer: ActorRef,
                                    dataOverrides: Seq[DataOverride[_]])
                                   (implicit mat: Materializer, ec: ExecutionContext)
      : Future[Source[MarketData[T], NotUsed]] = {

    // Build static data if not yet built.
    if (staticData.isEmpty) {
      staticData = dataSeqs(selection.timeRange.get)
    }

    // Return it.
    Future.successful(Source(staticData(selection.path.asInstanceOf[DataPath[Candle]])
      .asInstanceOf[Seq[MarketData[T]]].toIndexedSeq))
  }
}
