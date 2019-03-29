package flashbot.util

object NumberUtils {

  def round8(d: Double): Double =
    (d * 1e8 + .05).toLong / 1e8

  def round(d: Double, factor: Double) =
    (d * factor + .05).toLong / factor

}
