package flashbot.util

import flashbot.core.Instrument.Derivative
import flashbot.exchanges.BitMEX
import flashbot.models.core.{Order, OrderBook}
import org.scalatest.{FlatSpec, Matchers}

class MarginSpec extends FlatSpec with Matchers {

  private def _calcOrderMargin(pos: Double, orders: Seq[(Double, Double)],
                             leverage: Double, instrument: Derivative) = {
    round(Margin.calcOrderMargin(pos, leverage, orders.zipWithIndex.map {
      case ((qty, v), i) =>
        val price = instrument match {
          case BitMEX.ETHUSD =>
            v / qty / BitMEX.ETHUSD.bitcoinMultiplier
          case BitMEX.XBTUSD =>
            1.0 / (v / qty)
        }

        Order(i.toString, if (qty < 0) Order.Sell else Order.Buy, qty.abs, Some(price))
    }.foldLeft(OrderBook()) { case (book, order) => book.open(order) }, instrument))
  }

  def round(d: Double): Double = BigDecimal.valueOf(d)
    .setScale(6, BigDecimal.RoundingMode.HALF_UP).rounded.doubleValue()

  "MarginSpec" should "calculate order margin" in {

    _calcOrderMargin(25, Seq(
      (75, 0.0086), (-20, 0.0030), (50, 0.0060),
      (-10, 0.0040), (-15, 0.0024)
    ), 5.0, BitMEX.ETHUSD) shouldBe  round(0.0032883555555555553)

    _calcOrderMargin(25, Seq(
      (45, 0.0051), (-20, 0.0030), (50, 0.0060),
      (-10, 0.0040), (-15, 0.0024)
    ), 5.0, BitMEX.ETHUSD) shouldBe round(0.002588187134502924)

    _calcOrderMargin(25, Seq(
      (-20, 0.0030), (50, 0.0060), (-10, 0.0040), (-15, 0.0024)
    ), 5.0, BitMEX.ETHUSD) shouldBe round(0.0015555555555555555)

    _calcOrderMargin(25, Seq(
      (-20, 0.0030), (50, 0.0060), (-15, 0.0024)
    ), 5.0, BitMEX.ETHUSD) shouldBe round(0.0012685714285714286)

    _calcOrderMargin(0, Seq(
      (-20, 0.0032), (-50, 0.0150), (50, 0.0060)
    ), 5.0, BitMEX.ETHUSD) shouldBe round(0.00364)

    _calcOrderMargin(-50, Seq(
      (-20, 0.0032), (50, 0.0060)
    ), 5.0, BitMEX.ETHUSD) shouldBe round(0.00064)

    _calcOrderMargin(-50, Seq(
      (50, 0.0060)
    ), 5.0, BitMEX.ETHUSD) shouldBe 0

    _calcOrderMargin(-50, Seq(), 5.0, BitMEX.ETHUSD) shouldBe 0

    _calcOrderMargin(0, Seq(
      (-90, 0.0180), (70, 0.0233), (-30, 0.0071)
    ), 10.0, BitMEX.XBTUSD) shouldBe round(0.0033758333333333336)

    _calcOrderMargin(10, Seq(
      (-90, 0.0180), (70, 0.0233)
    ), 10.0, BitMEX.XBTUSD) shouldBe round(0.00253)

    _calcOrderMargin(10, Seq(
      (-90, 0.0180), (70, 0.0233), (-20, 0.0050)
    ), 10.0, BitMEX.XBTUSD) shouldBe round(0.0029572727272727277)

    _calcOrderMargin(-95, Seq(
      (70, 0.0233), (-20, 0.0050)
    ), 10.0, BitMEX.XBTUSD) shouldBe 0.0005
  }

}
