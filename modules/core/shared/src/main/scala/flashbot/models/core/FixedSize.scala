package flashbot.models.core

import flashbot.core.{AssetKey, InstrumentIndex, PriceIndex}
import flashbot.models.core.Order._

import scala.language.implicitConversions

//case class GenericFixedSize[T: Numeric](override val num: T,
//                                        override val security: String)
//    extends FixedSize[T](num, security) {
//  override def map(fn: Double => Double) = copy(num = fn(num))
//}

//trait MkFixedSize[T] {
//  def mk(num: T, security: String): FixedSize[T]
//}

case class FixedSize[T: Numeric](num: T, security: String) {

  val tn: Numeric[T] = implicitly[Numeric[T]]
  import tn._

  def isEmpty: Boolean = num equiv tn.fromInt(0)

  def qty: Double = math.abs(tn.toDouble(num))

  def side: Side = {
    if (num > tn.fromInt(0)) Buy
    else Sell
  }

  def map(fn: T => T): FixedSize[T] = FixedSize(fn(num), security)
}

object FixedSize {

//  implicit def mkFixedSize: MkFixedSize[Double] =
//    (num: Double, security: String) => FixedSize(num, security)

  implicit def buildFixedSizeStr(str: String): FixedSize[Double] = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }

  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSize[Double] =
    FixedSize(tuple._1, tuple._2)

//  def numeric[T: Numeric](amount: T, symbol: String): FixedSize[T] = FixedSize[T](amount, symbol)

  implicit class ConvertFixedSizeOps[T: Numeric](size: FixedSize[T]) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): FixedSize[Double] = {
      val n = implicitly[Numeric[T]]
      FixedSize(prices.convert(size.security, key).get.price * n.toDouble(size.num), key.security)
    }
  }

  implicit class ToFixedSizeOps[T: Numeric](qty: T) {
    def of(key: AssetKey): FixedSize[T] = FixedSize(qty, key.security)
  }

  def safeApply[T: Numeric](x: FixedSize[T], y: FixedSize[T],
                            impl: (T, T) => T): FixedSize[T] = {
    checkUnits(x, y)
    FixedSize(impl(x.num, y.num), x.security)
  }

  def checkUnits[T](x: FixedSize[T], y: FixedSize[T]): Unit = {
    if (x.security != y.security) {
      throw new UnsupportedOperationException("Could not perform numerical operation on " +
        s"FixedSizes of different securities: ($x, $y).")
    }
  }

  implicit def fixedSizeNumeric[T: Numeric]: Numeric[FixedSize[T]] =
    new Numeric[FixedSize[T]] {
      val impl = implicitly[Numeric[T]]
      override def plus(x: FixedSize[T], y: FixedSize[T]) = safeApply(x, y, impl.plus)
      override def minus(x: FixedSize[T], y: FixedSize[T]) = safeApply(x, y, impl.minus)
      override def times(x: FixedSize[T], y: FixedSize[T]) = safeApply(x, y, impl.times)
      override def negate(x: FixedSize[T]) = x.map(impl.negate)
      override def fromInt(x: Int) = FixedSize(impl.fromInt(x), "usd")
      override def toInt(x: FixedSize[T]) = impl.toInt(x.num)
      override def toLong(x: FixedSize[T]) = impl.toLong(x.num)
      override def toFloat(x: FixedSize[T]) = impl.toFloat(x.num)
      override def toDouble(x: FixedSize[T]) = impl.toDouble(x.num)
      override def compare(x: FixedSize[T], y: FixedSize[T]) = {
        checkUnits(x, y)
        impl.compare(x.num, y.num)
      }
    }


  implicit def fixedSizeG: Numeric[FixedSize[Double]] = fixedSizeNumeric[Double]

  val numericDouble = fixedSizeG
}
