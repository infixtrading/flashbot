package flashbot.util


object NumberUtils {

  def scale(d: Double): Int = BigDecimal(d.toString).scale

  def round8(d: Double): Double =
    (d * 1e8 + .05).toLong / 1e8

  def round(d: Double, scale: Double): Double = {
    val factor = math.pow(10, scale)
    (d * factor + .05).toLong / factor
  }

  def floor(d: Double, scale: Double): Double = {
    val factor = math.pow(10, scale)
    (d * factor).toLong / factor
  }

}
