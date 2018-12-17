package com.infixtrading.flashbot.models.core
import com.infixtrading.flashbot.core.{AssetKey, Conversions, InstrumentIndex, PriceIndex}

case class Balance(account: Account, qty: Double) {
  def size = FixedSize(qty, account.security)
  def assetKey: AssetKey = AssetKey(account)
}

object Balance {
  implicit class ConvertBalanceOps(balance: Balance) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): Balance = {
      val newPrice: FixedPrice = prices.conversions(balance.assetKey, key)
//      println("BALANCE", balance, key, newPrice)
      Balance(newPrice.pair._2.account.get, newPrice.price * balance.qty)
    }
  }
}
