package flashbot.models.core

import flashbot.core.{AssetKey, InstrumentIndex, PriceIndex}

case class Balance(account: Account, qty: Double) {
  def size = FixedSize(qty, account.security)
  def assetKey: AssetKey = AssetKey(account)
}

object Balance {
  implicit class ConvertBalanceOps(balance: Balance) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): Balance = {
      val newPrice = prices.convert(balance.assetKey, key).get
      val quoteKey = newPrice.pair._2
      Balance(quoteKey.account.getOrElse(balance.account), newPrice.price * balance.qty)
    }
  }
}
