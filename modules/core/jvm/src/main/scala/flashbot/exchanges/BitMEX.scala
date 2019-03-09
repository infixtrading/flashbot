package flashbot.exchanges

import akka.actor.ActorSystem
import akka.stream.Materializer
import flashbot.core.Instrument.{FuturesContract, Index}
import flashbot.models.core.Order.Fill
import flashbot.core._
import flashbot.models.core.{ExchangeResponse, FixedSize, OrderRequest}

import scala.concurrent.Future

class BitMEX(implicit val system: ActorSystem,
             val mat: Materializer) extends Exchange {

  override def makerFee: Double = -0.00025

  override def takerFee: Double = 0.00075

  override def cancel(id: String, pair: Instrument): Future[ExchangeResponse] = ???

  override def order(req: OrderRequest): Future[ExchangeResponse] = ???

  override def baseAssetPrecision(pair: Instrument): Int = ???

  override def quoteAssetPrecision(pair: Instrument): Int = ???

  override def lotSize(pair: Instrument): Option[Double] = ???

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

    override def markPrice(prices: PriceIndex) = 1.0 / prices(symbol)

    override def security = Some(symbol)

    // https://www.bitmex.com/app/seriesGuide/XBT#How-is-the-XBTUSD-Perpetual-Contract-Quoted
    override def pnl(size: Long, entryPrice: Double, exitPrice: Double) = {
      size * (1.0 / entryPrice - 1.0 / exitPrice)
    }

    override def valueDouble(price: Double) = 1.0 / price
  }

  object ETHUSD extends FuturesContract {
    override def symbol = "ethusd"
    override def base = "eth"
    override def quote = "usd"
    override def settledIn = Some("xbt")

    val bitcoinMultiplier: Double = 0.000001

    override def markPrice(prices: PriceIndex) = ???

    override def security = Some(symbol)

    // https://www.bitmex.com/app/seriesGuide/ETH#How-Is-The-ETHUSD-Perpetual-Contract-Quoted
    override def pnl(size: Long, entryPrice: Double, exitPrice: Double) = {
      (exitPrice - entryPrice) * bitcoinMultiplier * size
    }

    override def valueDouble(price: Double) = price * bitcoinMultiplier
  }

  object BXBT extends Index(".BXBT", "xbt", "usd")
}
