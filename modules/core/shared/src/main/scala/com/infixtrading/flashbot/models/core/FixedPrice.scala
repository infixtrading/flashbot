package com.infixtrading.flashbot.models.core
import com.infixtrading.flashbot.core.{AssetKey, HasSecurity, PriceIndex}

case class FixedPrice[T <: HasSecurity](price: Double, pair: (T, T)) {
  def base = pair._1
  def quote = pair._2
  def flip = FixedPrice(1 / price, (quote, base))

  def map[M <: HasSecurity](fn: T => M) = copy(pair = (fn(base), fn(quote)))

  def compose(next: FixedPrice[T])(implicit prices: PriceIndex): FixedPrice[T] = composeOpt(next).get
  def composeOpt(next: FixedPrice[T])(implicit prices: PriceIndex): Option[FixedPrice[T]] = {
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
  private def _compose(next: FixedPrice[T])(implicit prices: PriceIndex): Option[FixedPrice[T]] =
    if (prices.equiv(quote.security, next.base.security))
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
