
import java.time.Instant

import akka.NotUsed
import akka.stream.{ActorMaterializer, Materializer}
import akka.stream.scaladsl.JavaFlowSupport.Source
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.infixtrading.flashbot.core.DataSource.StreamSelection
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.core.{DataType, MarketData, TimeSeriesTap, Trade}
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.api.OrderTarget
import com.infixtrading.flashbot.models.core.Order.Buy
import com.infixtrading.flashbot.models.core._
import io.circe._
import io.circe.generic.auto._
import io.circe.generic.semiauto._
import org.jquantlib.math.distributions.NormalDistribution
import org.jquantlib.processes.GeometricBrownianMotionProcess

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
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


/**
  * A strategy that predicts data one step forwards in time.
  */
class LookAheadCandleStrategy extends Strategy with Predictor1[MarketData[Candle], Double] {

  case class Params(market: Market, sabotage: Boolean)

  override def paramsDecoder = deriveDecoder

  // Source the data from the strategy itself.
  val tr = TimeRange(0, Instant.EPOCH.plusMillis((5 days).toMillis).toEpochMilli * 1000)
  val path1 = DataPath("bitfinex", "eth_usd", "candles_5m")
  def dataSeqs(implicit mat: Materializer): Map[DataPath, Seq[MarketData[Candle]]] = Map(
    path1 -> Await.result(
      TimeSeriesTap.prices(90.0, .4, .2, tr, 5 minutes).map {
        case (instant, price) =>
          val micros = instant.toEpochMilli * 1000
          BaseMarketData(Candle(micros, price, price, price, price, None), path1, micros, 1)
      } .toMat(Sink.fold(Seq.empty[MarketData[Candle]]) {
        case (memo, md) => memo :+ md
      })(Keep.right).run(), 15 seconds)
  )

  var staticData = Map.empty[DataPath, Seq[MarketData[Candle]]]

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
      staticData = dataSeqs
      Seq.empty
    }
  }

  override def handleData(md: MarketData[_])(implicit ctx: TradingSession) = md.data match {
    case candle: Candle =>
      // If high confidence prediction, follow it blindly. If low, do nothing.
      val prediction = predict(md)
      if (prediction.confidence > .75) {
        val constraints = Seq(Portfolioprediction.prediction)
        val sym = md.path

        (if (prediction.prediction > candle.close) {
          Some(portfolio.manager.solve(
            Seq(s"long($sym).as(usd) == ${portfolio.maxBuyingPower(sym).as("usd")}")))

        } else if (prediction.prediction < candle.close) {
          Some(portfolio.manager.solve(
            Seq(s"short($sym).as(usd) == ${portfolio.maxSellingPower(sym).as("usd")}")))

        } else None) match {
          case Some(Success(orderTargets: Seq[OrderTarget])) =>
            for (t <- orderTargets) {
              ctx.send(t)
            }

          case Some(Failure(err)) =>
            _log.error(err)
//            throw err

          case None =>
        }
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
  override def resolveMarketData(streamSelection: StreamSelection)
                                (implicit mat: Materializer): Future[Option[Iterator[MarketData[_]]]] = {
    Future.successful((streamSelection.path match {
      case DataPath(_, "eth_usd", "candles_1h") =>
        Some(TimeSeriesTap.prices(90.0, .4, .2, streamSelection.timeRange, 5 minutes))
      case DataPath(_, "btc_usd", "candles_1h") =>
        Some(TimeSeriesTap.prices(3000.0, .6, .4, streamSelection.timeRange, 5 minutes))
      case _ => None
    }).map((src: Source[(Instant, Double), NotUsed]) => ???))
  }


}
