package flashbot

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

package object util {

  val longVal: Regex = raw"^([0-9]+)$$".r
  val rmDot: Regex = raw"^([0-9]+)\.0+$$".r
  val doubleVal: Regex = raw"^([0-9]+)(\.[0-9]*[1-9])0*$$".r

  /**
    * Removes the trailing zeroes (and the period, if applicable) from the string representation
    * of a number.
    */
  def stripTrailingZeroes(d: String): String = d match {
    case longVal(v: String) => v
    case rmDot(v: String) => v
    case doubleVal(a: String, b: String) => a + b
  }

  implicit class OptionOps[A](opt: Option[A]) {
    def toTry(err: Exception): Try[A] = opt.map(Success(_)) .getOrElse(Failure(err))
    def toTry(msg: String): Try[A] = toTry(new NoSuchElementException(msg))
  }

  implicit class OptionFutOps[A](opt: Option[A]) {
    def toFut(err: Exception): Future[A] = Future.fromTry(opt.toTry(err))
    def toFut(msg: String): Future[A] = Future.fromTry(opt.toTry(msg))
  }

  implicit class TryFutOps[A](t: Try[A]) {
    def toFut: Future[A] = Future.fromTry(t)
  }
}
