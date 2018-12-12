package com.infixtrading.flashbot

import org.scalatest.{FlatSpec, FunSpec, FunSuite, Matchers}

class TestLibSuite extends FlatSpec with Matchers {

  "assertTestLib" should "is a test lib" in {
    // All Float values should be encoded in a way that match the original value.
//    assert(Encoder[Float].apply(x).toString.toFloat === x)

    // Assert true
    true shouldBe true

    // Assert false

    // For floats which are NOT represented with scientific notation,
    // the JSON representaton should match Float.toString
    // This should catch cases where 1.2f would previously be encoded
    // as 1.2000000476837158 due to the use of .toDouble
//    if (!x.toString.toLowerCase.contains('e')) {
//      assert(Encoder[Float].apply(x).toString === x.toString)
//    }
  }
}
