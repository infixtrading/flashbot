
import com.infixtrading.flashbot.core.MarketData
import com.infixtrading.flashbot.engine.{SessionLoader, Strategy, TradingSession}
import com.infixtrading.flashbot.models.core.{Candle, DataPath, Portfolio}
import io.circe.Decoder
import io.circe.generic.semiauto._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class ScannerStrategy extends Strategy {

  case class Params()

  def paramsDecoder: Decoder[Params] = deriveDecoder

  override def title = "Empty Strategy"

  override def initialize(portfolio: Portfolio, loader: SessionLoader) = Future {
    Seq("bitfinex/btc_usd/candles")
  }

  override def handleData(md: MarketData[_])(implicit ctx: TradingSession) =
    md.data match {
      case candle: Candle =>
//        record("price", candle)
    }

//  override def resolveMarketData(address: DataPath) = address match {
//    case DataPath("bitfinex", "btc_usd", "candles") => Some(Seq().iterator)
//  }
}
