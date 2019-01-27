package flashbot.engine

import org.scalacheck.Properties
import org.scalacheck.Prop._

object StringGenTests extends Properties("String") {

  property("startsWith") = forAll { (a: String, b: String) =>
    (a+b).startsWith(a)
  }

  property("concatenate") = forAll { (a: String, b: String) =>
    (a+b).length >= a.length && (a+b).length >= b.length
  }

  property("substring") = forAll { (a: String, b: String, c: String) =>
    (a+b+c).substring(a.length, a.length+b.length) == b
  }

  def isUnder25(double: Double): Boolean = double < 25

  property("under_25") = forAll( (a: Option[Double]) => a match {
    case Some(vi) =>
      val v = vi % 100
      isUnder25(v) == (v < 25)

    case None => true
  })

}