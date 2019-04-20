package flashbot.core

import flashbot.models.Order._
import scala.language.implicitConversions

case class FixedSize(amount: BigDecimal, security: String) {
  lazy val tn: Numeric[BigDecimal] = implicitly[Numeric[BigDecimal]]
//  import tn._

//  def isEmpty: Boolean = amount equiv tn.fromInt(0)

//  def qty: Double = math.abs(tn.toDouble(amount))

  def side: Side = {
    if (tn.toDouble(amount) > 0) Buy
    else Sell
  }

  def map(fn: BigDecimal => BigDecimal): FixedSize = FixedSize(fn(amount), security)
}

object FixedSize {

  implicit def buildFixedSizeStr(str: String): FixedSize = {
    val parts = str.trim.split(" ")
    val symbol = parts.tail.mkString("")
    FixedSize(parts.head.toDouble, symbol)
  }

  implicit def buildFixedSizeFromTuple(tuple: (Double, String)): FixedSize =
    FixedSize(tuple._1, tuple._2)

  private val impl = implicitly[Fractional[BigDecimal]]

  implicit val FixedSizeFractional: Fractional[FixedSize] = new Fractional[FixedSize] {
    override def div(x: FixedSize, y: FixedSize) = safeApply(x, y, impl.div)
    override def plus(x: FixedSize, y: FixedSize) = safeApply(x, y, impl.plus)
    override def minus(x: FixedSize, y: FixedSize) = safeApply(x, y, impl.minus)
    override def times(x: FixedSize, y: FixedSize) = safeApply(x, y, impl.times)
    override def negate(x: FixedSize) = x.map(impl.negate)
    override def fromInt(x: Int) = FixedSize(impl.fromInt(x), "usd")
    override def toInt(x: FixedSize) = impl.toInt(x.amount)
    override def toLong(x: FixedSize) = impl.toLong(x.amount)
    override def toFloat(x: FixedSize) = impl.toFloat(x.amount)
    override def toDouble(x: FixedSize) = impl.toDouble(x.amount)
    override def compare(x: FixedSize, y: FixedSize) = {
      checkUnits(x, y)
      impl.compare(x.amount, y.amount)
    }
  }

  implicit def mkNumericOps(t: FixedSize) = FixedSizeFractional.mkNumericOps(t)
  implicit def mkOrderingOps(t: FixedSize) = FixedSizeFractional.mkOrderingOps(t)

  implicit class ConvertFixedSizeOps(size: FixedSize) {
    def as(key: AssetKey[_])(implicit prices: PriceIndex,
                             instruments: InstrumentIndex,
                             metrics: Metrics): FixedSize = {
      as(key, strict = false)
    }

    def as(key: AssetKey[_], strict: Boolean)
          (implicit prices: PriceIndex,
           instruments: InstrumentIndex,
           metrics: Metrics): FixedSize =
      FixedSize(asDouble(key, strict).toBigDecimial, key.security)

    def asDouble(key: AssetKey[_])
                (implicit prices: PriceIndex,
                 instruments: InstrumentIndex,
                 metrics: Metrics): Double = asDouble(key, strict = false)

    def asDouble(key: AssetKey[_], strict: Boolean)
                (implicit prices: PriceIndex,
              instruments: InstrumentIndex,
              metrics: Metrics): Double = {
      val price = prices.calcPrice(size.security, key, strict)
      price * size.amount.doubleValue()
    }
  }

  implicit class ToFixedSizeOps(qty: Double) {
    def of(key: AssetKey): FixedSize = FixedSize(qty.toBigDecimial, key.security)
  }


  def safeApply(x: FixedSize, y: FixedSize,
                impl: (BigDecimal, BigDecimal) => BigDecimal): FixedSize = {
    checkUnits(x, y)
    FixedSize(impl(x.amount, y.amount), x.security)
  }

  def checkUnits(x: FixedSize, y: FixedSize): Unit = {
    if (x.security != y.security) {
      throw new UnsupportedOperationException("Could not perform numerical operation on " +
        s"FixedSizes of different securities: ($x, $y).")
    }
  }

//  implicit def fixedSizeNumeric[T: Numeric]: Numeric[FixedSize[T]] =
//    new Numeric[FixedSize[T]] {
//      val impl = implicitly[Numeric[T]]
//      override def plus(x: FixedSize[T], y: FixedSize[T]) = safeApply(x, y, impl.plus)
//      override def minus(x: FixedSize[T], y: FixedSize[T]) = safeApply(x, y, impl.minus)
//      override def times(x: FixedSize[T], y: FixedSize[T]) = safeApply(x, y, impl.times)
//      override def negate(x: FixedSize[T]) = x.map(impl.negate)
//      override def fromInt(x: Int) = FixedSize(impl.fromInt(x), "usd")
//      override def toInt(x: FixedSize[T]) = impl.toInt(x.amount)
//      override def toLong(x: FixedSize[T]) = impl.toLong(x.amount)
//      override def toFloat(x: FixedSize[T]) = impl.toFloat(x.amount)
//      override def toDouble(x: FixedSize[T]) = impl.toDouble(x.amount)
//      override def compare(x: FixedSize[T], y: FixedSize[T]) = {
//        checkUnits(x, y)
//        impl.compare(x.amount, y.amount)
//      }
//    }


//  implicit def fixedSizeG: Numeric[FixedSize[Double]] = fixedSizeNumeric[Double]
//
//  val numericDouble = fixedSizeG

//  implicit def numToFixedSize(num: Num): FixedSize =
//    FixedSize(num.toBigDecimial, "")

//  implicit def toFixedSize[T: Numeric](double: T): FixedSize =
//    FixedSize(BigDecimal(double.toString), "")
}
