package com.infixtrading.flashbot.models.core
import com.infixtrading.flashbot.core.{AssetKey, PriceIndex}

case class FixedPrice(price: Double, pair: (AssetKey, AssetKey)) {
  def base = pair._1
  def quote = pair._2
  def flip = FixedPrice(1 / price, (quote, base))

  def compose(next: FixedPrice)(implicit prices: PriceIndex): FixedPrice = composeOpt(next).get
  def composeOpt(next: FixedPrice)(implicit prices: PriceIndex): Option[FixedPrice] = {
    // Try to line up the markets as-is.
    _compose(next)
      // If that fails, try to flip next market
      .orElse(_compose(next.flip))
      // Next try to flip the current market
      .orElse(this.flip._compose(next))
      // Finally try to flip both markets
      .orElse(this.flip._compose(next.flip))
  }

  /**
    * Compose only if the markets line up. Don't try to flip them.
    * Markets align if the current quote is the next base: usd/btc -> btc/eth
    */
  private def _compose(next: FixedPrice)(implicit prices: PriceIndex): Option[FixedPrice] =
    if (prices.equiv(quote.symbol, next.base.symbol))
      {
//        println("===============")
//        println(s"Composing ${this} -> $next")
//        println(s"Matched $quote to ${next.base}")
//        println(s"Using price ${price} * ${next.price} = ${price * next.price} (${base}::${next.quote})")
//        println("===============")
        Some(FixedPrice(price * next.price, (base, next.quote)))
      }
    else None
}
