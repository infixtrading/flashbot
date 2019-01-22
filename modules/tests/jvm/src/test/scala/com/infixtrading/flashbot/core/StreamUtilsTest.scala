package com.infixtrading.flashbot.core

import org.scalatest.{FlatSpec, Matchers}
import com.infixtrading.flashbot.util.stream._

class StreamUtilsTest extends FlatSpec with Matchers {
  "dropDuplicates" should "work" in {
    val vals = Seq(1, 2, 3, 3, 4, 5)
    vals.toStream.dropDuplicates.toList shouldEqual vals.distinct
  }
}
