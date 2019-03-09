package flashbot.core

import org.scalatest.{FlatSpec, FunSpec, FunSuite, Matchers}

class ArithmeticBenchmark extends FlatSpec with Matchers {

  "Long" should "should be faster than double" in {
    var a: Long = 1
    var b: Long = 2
    var count: Long = 0
    var temp: Long = 0

    val startNanos = System.nanoTime()
    while (count < 100000000) {
      temp = b
      b = (a + b) % 1000000
      a = temp
      count = count + 1
    }

    println(b, (System.nanoTime() - startNanos) / 1000000)

    var ad: Double = 1.0
    var bd: Double = 2.0
    var countd: Long = 0
    var tempd: Double = 0

    val startNanosd = System.nanoTime()
    while (countd < 100000000) {
      tempd = bd
      bd = (ad + bd) % 1000000
      ad = tempd
      countd = countd + 1
    }

    println(bd, (System.nanoTime() - startNanosd) / 1000000)

    println(Math.pow(2, 63 - 12))

  }

}
