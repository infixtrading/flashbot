package flashbot.models

import flashbot.core._

//case class Balance(account: Account, qty: Double) {
//  def size: FixedSize[Double] = FixedSize(qty, account.security)
//  def assetKey: AssetKey = AssetKey(account)
//}
//
//object Balance {
//  implicit class ConvertBalanceOps(balance: Balance) {
//    def asDouble(key: AssetKey)(implicit prices: PriceIndex,
//                                instruments: InstrumentIndex,
//                                metrics: Metrics): Double = {
//      val newPrice = prices.calcPrice(balance.assetKey, key)
//      newPrice * balance.qty
////      val quoteKey = newPrice.pair._2
////      Balance(quoteKey.account.getOrElse(balance.account), newPrice.price * balance.qty)
//    }
//  }
//}
