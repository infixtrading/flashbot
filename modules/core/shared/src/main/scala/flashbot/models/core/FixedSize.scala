package flashbot.models.core

import flashbot.core.{AssetKey, InstrumentIndex, PriceIndex}
import flashbot.models.core.Order._

import scala.language.implicitConversions

case class FixedSize(override val num: Double,
                     override val security: String)
    extends GenericFixedSize[Double](num, security) {
  override def map(fn: Double => Double) = copy(num = fn(num))
}

trait MkFixedSize[T] {
  def mk(num: T, security: String): GenericFixedSize[T]
}

abstract class GenericFixedSize[T: Numeric](val num: T, val security: String) {

  val tn: Numeric[T] = implicitly[Numeric[T]]
  import tn._

  def isEmpty: Boolean = num equiv tn.fromInt(0)

  def qty: Double = math.abs(tn.toDouble(num))

  def side: Side = {
    if (num > tn.fromInt(0)) Buy
    else Sell
  }

  def map(fn: T => T): GenericFixedSize[T]
}

object FixedSize {

  implicit def mkFixedSize: MkFixedSize[Double] =
    (num: Double, security: String) => FixedSize(num, security)

  implicit def buildFixedSizeStr(str: String): FixedSize = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }

  implicit def buildFixedSizeFromTuple[T: MkFixedSize](tuple: (T, String)): GenericFixedSize[T] =
    implicitly[MkFixedSize[T]].mk(tuple._1, tuple._2)

//  def numeric[T: Numeric](amount: T, symbol: String): FixedSize[T] = FixedSize[T](amount, symbol)

  implicit class ConvertFixedSizeOps[T: Numeric](size: GenericFixedSize[T]) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): FixedSize = {
      val n = implicitly[Numeric[T]]
      FixedSize(prices.convert(size.security, key).get.price * n.toDouble(size.num), key.security)
    }
  }

  implicit class ToFixedSizeOps[T: MkFixedSize](qty: T) {
    def of(key: AssetKey): GenericFixedSize[T] = (qty, key.security)
  }

  def safeApply[T: Numeric : MkFixedSize](x: GenericFixedSize[T],
                                          y: GenericFixedSize[T],
                                          impl: (T, T) => T): GenericFixedSize[T] = {
    checkUnits(x, y)
    (impl(x.num, y.num), x.security)
  }

  def checkUnits[T](x: GenericFixedSize[T], y: GenericFixedSize[T]): Unit = {
    if (x.security != y.security) {
      throw new UnsupportedOperationException("Could not perform numerical operation on " +
        s"FixedSizes of different securities: ($x, $y).")
    }
  }

  implicit def fixedSizeNumeric[T: Numeric](implicit mkFixedSize: MkFixedSize[T]): Numeric[GenericFixedSize[T]] =
    new Numeric[GenericFixedSize[T]] {
      val impl = implicitly[Numeric[T]]
      override def plus(x: GenericFixedSize[T], y: GenericFixedSize[T]) = safeApply(x, y, impl.plus)
      override def minus(x: GenericFixedSize[T], y: GenericFixedSize[T]) = safeApply(x, y, impl.minus)
      override def times(x: GenericFixedSize[T], y: GenericFixedSize[T]) = safeApply(x, y, impl.times)
      override def negate(x: GenericFixedSize[T]) = x.map(impl.negate)
      override def fromInt(x: Int) = (impl.fromInt(x), "usd")
      override def toInt(x: GenericFixedSize[T]) = impl.toInt(x.num)
      override def toLong(x: GenericFixedSize[T]) = impl.toLong(x.num)
      override def toFloat(x: GenericFixedSize[T]) = impl.toFloat(x.num)
      override def toDouble(x: GenericFixedSize[T]) = impl.toDouble(x.num)
      override def compare(x: GenericFixedSize[T], y: GenericFixedSize[T]) = {
        checkUnits(x, y)
        impl.compare(x.num, y.num)
      }
    }


  implicit def fixedSizeD: Numeric[FixedSize] = fixedSizeNumeric[Double].asInstanceOf[Numeric[FixedSize]]

  val numericDouble = fixedSizeD
}
