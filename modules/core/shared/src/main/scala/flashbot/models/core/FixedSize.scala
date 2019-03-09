package flashbot.models.core

import flashbot.core.{AssetKey, InstrumentIndex, Metrics, PriceIndex}
import flashbot.models.core.Order._

import scala.language.implicitConversions

case class FixedSize[T: Numeric](amount: T, security: String) {

  val tn: Numeric[T] = implicitly[Numeric[T]]
  import tn._

  def isEmpty: Boolean = amount equiv tn.fromInt(0)

  def qty: Double = math.abs(tn.toDouble(amount))

  def side: Side = {
    if (amount > tn.fromInt(0)) Buy
    else Sell
  }

  def map(fn: T => T): FixedSize[T] = FixedSize(fn(amount), security)
}

object FixedSize {

  implicit def buildFixedSizeStr(str: String): FixedSize[Double] = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }

  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSize[Double] =
    FixedSize(tuple._1, tuple._2)

  implicit class ConvertFixedSizeOps[T: Numeric](size: FixedSize[T]) {
    def as(key: AssetKey)(implicit prices: PriceIndex,
                          instruments: InstrumentIndex,
                          metrics: Metrics): FixedSize[Double] = {
      as(key, strict = false)
    }

    def as(key: AssetKey, strict: Boolean)
          (implicit prices: PriceIndex,
           instruments: InstrumentIndex,
           metrics: Metrics): FixedSize[Double] =
      FixedSize[Double](asDouble(key, strict), key.security)

    def asDouble(key: AssetKey)
                (implicit prices: PriceIndex,
                 instruments: InstrumentIndex,
                 metrics: Metrics): Double = asDouble(key, strict = false)

    def asDouble(key: AssetKey, strict: Boolean)
                (implicit prices: PriceIndex,
                 instruments: InstrumentIndex,
                 metrics: Metrics): Double = {
      val price = prices.calcPrice(size.security, key, strict)
      price * size.amount.asInstanceOf[Double]
    }
  }

  implicit class ToFixedSizeOps[T: Numeric](qty: T) {
    def of(key: AssetKey): FixedSize[T] = FixedSize(qty, key.security)
  }

  def safeApply[T: Numeric](x: FixedSize[T], y: FixedSize[T],
                            impl: (T, T) => T): FixedSize[T] = {
    checkUnits(x, y)
    FixedSize(impl(x.amount, y.amount), x.security)
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
      override def toInt(x: FixedSize[T]) = impl.toInt(x.amount)
      override def toLong(x: FixedSize[T]) = impl.toLong(x.amount)
      override def toFloat(x: FixedSize[T]) = impl.toFloat(x.amount)
      override def toDouble(x: FixedSize[T]) = impl.toDouble(x.amount)
      override def compare(x: FixedSize[T], y: FixedSize[T]) = {
        checkUnits(x, y)
        impl.compare(x.amount, y.amount)
      }
    }


  implicit def fixedSizeG: Numeric[FixedSize[Double]] = fixedSizeNumeric[Double]

  val numericDouble = fixedSizeG

  implicit def toFixedSize[T: Numeric](num: T): FixedSize[T] =
    FixedSize(num, "")
}
