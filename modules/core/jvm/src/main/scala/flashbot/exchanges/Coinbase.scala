package flashbot.exchanges
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import flashbot.core.Instrument.CurrencyPair
import flashbot.core.Exchange
import flashbot.core.Instrument
import flashbot.models.core.OrderRequest

import scala.concurrent.Future

class Coinbase(implicit val system: ActorSystem,
               val mat: Materializer) extends Exchange {

  override implicit val ec = scala.concurrent.ExecutionContext.global

  override def makerFee = .0000
  override def takerFee = .003
  override def order(req: OrderRequest) = ???
  override def cancel(id: String, pair: Instrument) = ???


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

  override def fetchPortfolio = Future.successful((Map.empty, Map.empty))
}
