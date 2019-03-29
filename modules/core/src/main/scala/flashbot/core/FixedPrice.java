class FixedPrice[B: AssetKey, Q: AssetKey](var price: Double,
                                           val pair: (B, Q),
                                           val flipped: Boolean = false) {

  this.price = round8(price)

  def base = pair._1
  def quote = pair._2

  def flip: FixedPrice[Q, B] =
    new FixedPrice[Q, B](1.0 / price, (quote, base), !flipped)

  def setPrice(p: Double): Unit = {
    this.price = p
  }

//  def flip = FixedPrice(1 / price, (quote, base))

//  def map[M <: HasSecurity](fn: T => M) = copy(pair = (fn(base), fn(quote)))

//  def compose(next: FixedPrice[T])
//             (implicit prices: PriceIndex,
//              metrics: Metrics): FixedPrice[T] = composeOpt(next).get

//  def composeOpt(next: FixedPrice[T])
//                (implicit prices: PriceIndex,
//                 metrics: Metrics): Option[FixedPrice[T]] = {
//    // Try to line up the markets as-is.
//    _compose(next)
//      // If that fails, try to flip next market
//      .orElse(_compose(next.flip))
//      // Next try to flip the current market
//      .orElse(this.flip._compose(next))
//      // Finally try to flip both markets
//      .orElse(this.flip._compose(next.flip))
//  }
//
//  /**
//    * Compose only if the markets line up. Don't try to flip them.
//    * Markets align if the current quote is the next base: usd/btc -> btc/eth
//    */
//  private def _compose(next: FixedPrice[T])
//                      (implicit prices: PriceIndex,
//                       metrics: Metrics): Option[FixedPrice[T]] =
//    if (prices.equiv(quote.security, next.base.security))
//        Some(new FixedPrice(price * next.price, (base, next.quote)))
//    else None

}
