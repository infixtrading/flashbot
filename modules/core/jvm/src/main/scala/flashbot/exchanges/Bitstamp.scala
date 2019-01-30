package flashbot.exchanges

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import flashbot.core.Exchange
import io.circe.Json
import flashbot.core.Instrument.CurrencyPair
import flashbot.core.Instrument
import flashbot.models.core._

import scala.concurrent.Future

class Bitstamp(implicit val system: ActorSystem,
               val mat: Materializer) extends Exchange {

  override implicit val ec = scala.concurrent.ExecutionContext.global

  override def makerFee: Double = .0005

  override def takerFee: Double = .0005

  override def order(req: OrderRequest): Future[ExchangeResponse] = ???

  override def cancel(id: String, pair: Instrument): Future[ExchangeResponse] = ???

  override def baseAssetPrecision(pair: Instrument): Int = pair match {
    case CurrencyPair("eur", "usd") => 5
    case _ => 8
  }

  override def quoteAssetPrecision(pair: Instrument): Int = pair match {
    case CurrencyPair("xrp", "eur") => 5
    case CurrencyPair("xrp", "usd") => 5
    case CurrencyPair("eur", "usd") => 5
    case CurrencyPair(_, "usd") => 2
    case CurrencyPair(_, "eur") => 2
    case _ => 8
  }

  override def lotSize(pair: Instrument): Option[Double] = None
  //    case Pair(_, "usd") => 5
  //    case Pair(_, "eur") => 5
  //    case Pair(_, "btc") => .001
  //  })

  override def fetchPortfolio = Future.successful((Map.empty, Map.empty))
}
