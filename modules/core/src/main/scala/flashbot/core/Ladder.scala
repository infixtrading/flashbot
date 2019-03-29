package flashbot.core
class Ladder(val depth: Int, val tickSize: Option[Double] = None) {
  import Ladder._

  private val asks: DoubleMap = new DoubleMap(depth, reverse = false)
  private val bids: DoubleMap = new DoubleMap(depth, reverse = true)

  def updateLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder =
    side match {
      case Bid =>
        updateMap(bids, priceLevel, quantity)
        this
      case Ask =>
        updateMap(asks, priceLevel, quantity)
        this
    }

  def increaseLevel(side: QuoteSide, priceLevel: Double, quantity: Double): Ladder =
    updateLevel(side, priceLevel, quantityAtPrice(priceLevel) + quantity)

  def spread: Double = asks.firstKey - bids.firstKey

  def quantityAtPrice(price: Double): Double = {
    var q = asks.get(price)
    if (java.lang.Double.isNaN(q))
      q = bids.get(price)
    q
  }

  def map(fn: ((Double, Double)) => (Double, Double)): Ladder =
    copy(asks = asks.map(fn), bids = bids.map(fn))

  def aggregate(depth: Int, tickSize: Double): Ladder = {
    throw new NotImplementedError()
  }

  def intersect(other: Ladder): Ladder = {
    assert(depth == other.depth, "Only ladders of the same depth can be intersected.")
    assert(tickSize == other.tickSize, "Only ladders with the same tick size can be intersected.")
    assert(tickSize.isDefined, "Ladders must have a tick size to be intersected.")

    copy(
      asks = intersectDepths(asks, other.bids),
      bids = intersectDepths(bids, other.asks)
    )
  }

  def priceSet: Set[Double] = bids.keySet ++ asks.keySet

  private def putOrder(order: Order): Ladder =
    updateLevel(order.side.toQuote, order.price.get, order.amount)
}
