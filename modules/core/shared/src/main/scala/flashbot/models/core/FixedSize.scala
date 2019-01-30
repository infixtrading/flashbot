package flashbot.models.core

import flashbot.core.{AssetKey, InstrumentIndex, PriceIndex}
import flashbot.models.core.Order._

import scala.language.implicitConversions

case class FixedSize(override val num: Double,
                     override val security: String)
    extends GenericFixedSize[Double](num, security)

class GenericFixedSize[T: Numeric](val num: T, val security: String) {

  val tn: Numeric[T] = implicitly[Numeric[T]]
  import tn._

  def isEmpty: Boolean = num equiv tn.fromInt(0)

  def qty: Double = math.abs(tn.toDouble(num))

  def side: Side = {
    if (num > tn.fromInt(0)) Buy
    else Sell
  }

  def map(fn: T => T): this.type = new GenericFixedSize[T](fn(num), security)
}

object FixedSize {

  implicit def buildFixedSizeStr(str: String): FixedSize = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }
  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSize =
    FixedSize(tuple._1, tuple._2)

  def numeric[T: Numeric](amount: T, symbol: String): GenericFixedSize[T] =
    new GenericFixedSize[T](amount, symbol)

  implicit class ConvertFixedSizeOps(size: FixedSize) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex): FixedSize =
      FixedSize(prices.convert(size.security, key).get.price * size.qty, key.security)
  }

  implicit class ToFixedSizeOps(qty: Double) {
    def of(key: AssetKey): FixedSize = FixedSize(qty, key.security)
  }

  def checkUnits[T: Numeric, O: Numeric](x: GenericFixedSize[T],
                                         y: GenericFixedSize[T],
                                         impl: (T, T) => O): GenericFixedSize[O] = {
    if (x.security != y.security) {
      throw new UnsupportedOperationException("Could not perform numerical operation on " +
        s"FixedSizes of different securities: ($x, $y).")
    }
    FixedSize.numeric(impl(x, y), x.security)
  }

  implicit def fixedSizeNumeric[T: Numeric]: Numeric[GenericFixedSize[T]] =
    new Numeric[GenericFixedSize[T]] {
      val impl = implicitly[Numeric[T]]
      override def plus(x: GenericFixedSize[T], y: GenericFixedSize[T]) = checkUnits(x, y, impl.plus)
      override def minus(x: GenericFixedSize[T], y: GenericFixedSize[T]) = checkUnits(x, y, impl.minus)
      override def times(x: GenericFixedSize[T], y: GenericFixedSize[T]) = checkUnits(x, y, impl.times)
      override def negate(x: GenericFixedSize[T]) = x.map(impl.negate)
      override def fromInt(x: Int) = FixedSize.numeric(impl.fromInt(x), "usd")
      override def toInt(x: GenericFixedSize[T]) = impl.toInt(x.num)
      override def toLong(x: GenericFixedSize[T]) = impl.toLong(x.num)
      override def toFloat(x: GenericFixedSize[T]) = impl.toFloat(x.num)
      override def toDouble(x: GenericFixedSize[T]) = impl.toDouble(x.num)
      override def compare(x: GenericFixedSize[T], y: GenericFixedSize[T]) =
        checkUnits[T, Int](x, y, impl.compare).num
    }

  implicit def fixedSizeD: Numeric[FixedSize] = implicitly[Numeric[GenericFixedSize[Double]]]
    .asInstanceOf[Numeric[FixedSize]]

//  def apply[T: Numeric] = implicitly[Numeric[FixedSize[T]]]

  val dNumeric = implicitly[Numeric[FixedSize]]
}
