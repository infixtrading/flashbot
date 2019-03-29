package flashbot.core

import scalaz.@@

//import io.circe.{Decoder, Encoder}
//
//import scala.language.implicitConversions
//import scala.math.BigDecimal.RoundingMode
//
//object Num {
//  private val precision: Int = 8
//  private val scale: Long = Math.pow(10, precision).toLong
//
//  val `1`: Num = 1.num
//  val `-1`: Num = (-1).num
//  val `0.1`: Num = .1.num
//  val `0`: Num = 0.num
//  val NaN: Num = new Num(Long.MinValue)
//
//  implicit val NumNumeric: Fractional[Num] = new Fractional[Num] {
//    override def plus(x: Num, y: Num) = new Num(x.value + y.value)
//
//    override def minus(x: Num, y: Num) = new Num(x.value - y.value)
//
//    override def times(x: Num, y: Num) = new Num(x.value * y.value)
//
//    override def negate(x: Num) = new Num(-x.value)
//
//    override def fromInt(x: Int) = new Num(x * scale)
//
//    override def toInt(x: Num) = toLong(x).toInt
//
//    override def toLong(x: Num) = x.value / scale
//
//    override def toFloat(x: Num) = toInt(x).toFloat
//
//    override def toDouble(x: Num) = toLong(x).toDouble
//
//    override def compare(x: Num, y: Num) = Ordering[Long].compare(x.value, y.value)
//
//    override def div(x: Num, y: Num) = new Num(x.value / y.value)
//  }
//
//  implicit class NumDoubleOps(d: Double) {
//    def num: Num = new Num((d * scale).toLong)
//  }
//
//  implicit class NumLongOps(l: Long) {
//    def num: Num = new Num(l * scale)
//  }
//
//  implicit class BigDecimalOps(bd: BigDecimal) {
//    def num: Num = bd.doubleValue().num
//  }
//
//  implicit def mkNumericOps(t: Num) = NumNumeric.mkNumericOps(t)
//  implicit def mkOrderingOps(t: Num) = NumNumeric.mkOrderingOps(t)
//
//  class Num(val value: Long) extends AnyVal {
//    def toBigDecimial: BigDecimal = BigDecimal.valueOf(value)
//      .setScale(precision, RoundingMode.HALF_DOWN)
//
//    def isWholeNumber: Boolean = value % scale == 0
//    def isNaN: Boolean = value == NaN.value
//    def isValid: Boolean = !isNaN
//
//    def convert(from: AssetKey, to: AssetKey)
//               (implicit prices: PriceIndex,
//                instruments: InstrumentIndex,
//                metrics: Metrics): Num = convert(from, to, strict = false)
//
//    def convert(from: AssetKey, to: AssetKey, strict: Boolean)
//               (implicit prices: PriceIndex,
//                instruments: InstrumentIndex,
//                metrics: Metrics): Num = ???
//
//    def getOrElse: Num =
//      if (isValid) value
//  }
//
//  implicit val numEncoder: Encoder[Num] = Encoder.encodeDouble.contramap(_.toDouble)
//  implicit val numDecoder: Decoder[Num] = Decoder.decodeDouble.map(_.num)
//
//  object ImplicitConversions {
//    implicit def doubleToNum(d: Double) = d.num
//    implicit def intToNum(i: Int) = i.toLong.num
//    implicit def longToNum(l: Long) = l.num
//    implicit def bigDecimalToNum(d: BigDecimal) = d.num
//  }
//}


