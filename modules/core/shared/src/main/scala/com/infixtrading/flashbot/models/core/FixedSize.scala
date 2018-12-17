package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.{AssetKey, Conversions, InstrumentIndex, PriceIndex}
import com.infixtrading.flashbot.models.core.Order.{Buy, Sell, Side}

case class FixedSize[T: Numeric](num: T, security: String) {
  val tn: Numeric[T] = implicitly[Numeric[T]]
  import tn._

  def isEmpty: Boolean = num equiv tn.fromInt(0)

  def qty: Double = math.abs(tn.toDouble(num))

  def side: Side = {
    if (num > tn.fromInt(0)) Buy
    else Sell
  }

  def map(fn: T => T) = {
    copy(num = fn(num))
  }
}

object FixedSize {

  type FixedSizeD = FixedSize[Double]

  implicit def buildFixedSizeStr(str: String): FixedSizeD = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }
  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSizeD =
    FixedSize(tuple._1, tuple._2)

//  def apply[T: Numeric](amount: T, symbol: String): FixedSize[T] =
//    {
//      println("apply FixedSize[T]", amount, symbol)
//      FixedSize(amount, symbol)
//    }

  implicit class ConvertFixedSizeOps(size: FixedSizeD) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): FixedSizeD =
      FixedSize(prices.conversions(size.security, key).price * size.qty, key.symbol)
  }

  implicit class ToFixedSizeOps(qty: Double) {
    def of(key: AssetKey): FixedSizeD = FixedSize(qty, key.symbol)
  }

  def checkUnits[T: Numeric, O: Numeric](x: FixedSize[T],
                                         y: FixedSize[T],
                                         impl: (T, T) => O): FixedSize[O] = {
    if (x.security != y.security) {
      throw new UnsupportedOperationException("Could not perform numerical operation on " +
        s"FixedSizes of different securities: ($x, $y).")
    }
    FixedSize(impl(x.num, y.num), x.security)
  }

  implicit def fixedSizeNumeric[T: Numeric]: Numeric[FixedSize[T]] =
    new Numeric[FixedSize[T]] {
      val impl = implicitly[Numeric[T]]
      override def plus(x: FixedSize[T], y: FixedSize[T]) = checkUnits(x, y, impl.plus)
      override def minus(x: FixedSize[T], y: FixedSize[T]) = checkUnits(x, y, impl.minus)
      override def times(x: FixedSize[T], y: FixedSize[T]) = checkUnits(x, y, impl.times)
      override def negate(x: FixedSize[T]) = x.map(impl.negate)
      override def fromInt(x: Int) = FixedSize(impl.fromInt(x), "usd")
      override def toInt(x: FixedSize[T]) = impl.toInt(x.num)
      override def toLong(x: FixedSize[T]) = impl.toLong(x.num)
      override def toFloat(x: FixedSize[T]) = impl.toFloat(x.num)
      override def toDouble(x: FixedSize[T]) = impl.toDouble(x.num)
      override def compare(x: FixedSize[T], y: FixedSize[T]) =
        checkUnits[T, Int](x, y, impl.compare).num
    }

  def apply[T: Numeric] = implicitly[Numeric[FixedSize[T]]]

  val dNumeric = implicitly[Numeric[FixedSizeD]]
}
