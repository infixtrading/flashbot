package flashbot.util

import spire.syntax.cfor._

class DoubleMap(maxSize: Int, reverse: Boolean) {

  private var size: Int = 0
  private val keys: Array[Double] = Array(maxSize)
  private val vals: Array[Double] = Array(maxSize)

  def update(removals: Array[Double], insertions: Array[Double]) = {
    cfor(0)(_ < size, _ + 1) { i =>
      // TODO
    }
  }

  def get(key: Double): Double = {
    val index = util.Arrays.binarySearch(keys, key)
    if (index >= 0) vals(index)
    else Double.NaN
  }

  def isEmpty = size == 0

  def firstKey: Double =
    if (isEmpty) Double.NaN
    else keys(0)

  def lastKey: Double =
    if (isEmpty) Double.NaN
    else keys(size - 1)

  def orderedIndex(i: Int): Int =
    if (reverse) size - i - 1
    else i

}
