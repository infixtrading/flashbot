package flashbot.exchanges

import akka.actor.ActorSystem
import akka.stream.Materializer
import flashbot.core.Instrument.{FuturesContract, Index}
import flashbot.core.Num._
import flashbot.models.core.Order.Fill
import flashbot.core._
import flashbot.models.core.{ExchangeResponse, OrderRequest}

import scala.concurrent.Future

class BitMEX(implicit val system: ActorSystem,
             val mat: Materializer) extends Exchange {

  override def makerFee: Num = -0.00025.num

  override def takerFee: Num = 0.00075.num

  override def cancel(id: String, pair: Instrument): Future[ExchangeResponse] = ???

  override def order(req: OrderRequest): Future[ExchangeResponse] = ???

  override def baseAssetPrecision(pair: Instrument): Int = ???

  override def quoteAssetPrecision(pair: Instrument): Int = ???

  override def lotSize(pair: Instrument): Option[Num] = ???

  override def instruments =
    Future.successful(Set(BitMEX.XBTUSD, BitMEX.ETHUSD))

  override def fetchPortfolio = Future.successful((Map.empty, Map.empty))
}

object BitMEX {

  object XBTUSD extends FuturesContract {
    override def symbol = "xbtusd"
    override def base = "xbt"
    override def quote = "usd"
    override def settledIn = Some("xbt")

//    override def markPrice(prices: PriceIndex) = 1.0 / prices(symbol)

    override def security = Some(symbol)

    // https://www.bitmex.com/app/seriesGuide/XBT#How-is-the-XBTUSD-Perpetual-Contract-Quoted
    override def pnl(size: Num, entryPrice: Num, exitPrice: Num) = {
      size * (`1` / entryPrice - `1` / exitPrice)
    }

    override def value(price: Num) = `1` / price
  }

  object ETHUSD extends FuturesContract {
    override def symbol = "ethusd"
    override def base = "eth"
    override def quote = "usd"
    override def settledIn = Some("xbt")

    val bitcoinMultiplier: Num = 0.000001.num

//    override def markPrice(prices: PriceIndex) = ???

    override def security = Some(symbol)

    // https://www.bitmex.com/app/seriesGuide/ETH#How-Is-The-ETHUSD-Perpetual-Contract-Quoted
    override def pnl(size: Num, entryPrice: Num, exitPrice: Num) = {
      (exitPrice - entryPrice) * bitcoinMultiplier * size
    }

    override def value(price: Num) = price * bitcoinMultiplier
  }

  object BXBT extends Index(".BXBT", "xbt", "usd")
}
