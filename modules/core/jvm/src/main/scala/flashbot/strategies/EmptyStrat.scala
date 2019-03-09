package flashbot.strategies

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import flashbot.core.DataType.{CandlesType, TradesType}
import flashbot.core.MarketData.BaseMarketData
import flashbot.core._
import flashbot.models.api.{DataOverride, DataSelection}
import flashbot.models.core.{DataPath, Market, Portfolio}
import com.github.andyglow.jsonschema.AsCirce._
import flashbot.server.ServerMetrics
import io.circe.generic.JsonCodec
import io.circe.parser._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

@JsonCodec
case class EmptyParams(markets: String, datatype: String)

class EmptyStrat extends Strategy[EmptyParams] with TimeSeriesMixin {

  val Trades = "trades"
  val Candles = "candles"

  override def decodeParams(paramsStr: String) = decode[EmptyParams](paramsStr).toTry

  override def title = "Empty Strategy"

  override def initialize(portfolio: Portfolio, loader: EngineLoader) = {
    val dataType = params.datatype match {
      case Candles => CandlesType(sessionBarSize)
      case Trades => TradesType
    }
    Future.successful(params.markets.split(",").map(Market(_).path(dataType)))
  }

  override def handleData(data: MarketData[_])(implicit ctx: TradingSession) = {
  }

  override def resolveMarketData[T](selection: DataSelection[T],
                                    dataServer: ActorRef,
                                    dataOverrides: Seq[DataOverride[Any]])
                                   (implicit mat: Materializer, ec: ExecutionContext) = {
    selection.path.datatype match {
      case CandlesType(d) if d > 1.minute =>
        val newPath = selection.path.withType(CandlesType(1.minute))
        super.resolveMarketData(selection.copy(path = newPath), dataServer, dataOverrides)
          .map(_.map(_.data)
            .via(TimeSeriesTap.aggregateCandles(d))
            .zipWithIndex
            .map { case (candle, i) =>
                BaseMarketData(candle.asInstanceOf[T], selection.path, candle.micros, 1, i)
            }
          )
      case _ =>
        super.resolveMarketData(selection, dataServer, dataOverrides)
    }
  }

  override def info(loader: EngineLoader) = {
    implicit val ec: ExecutionContext = loader.ec
    super.info(loader).map(
      _.withSchema(json.Json.schema[EmptyParams].asCirce().noSpaces)
        .withParamOptions("datatype", Seq(Trades, Candles), Candles)
    )
  }
}
