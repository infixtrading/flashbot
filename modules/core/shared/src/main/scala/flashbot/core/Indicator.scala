package flashbot.core

/**
  * This class is a WIP. Don't use it yet. Use TA4J directly instead.
  */
trait Indicator[T] {
  def minBars: Int
  def calculate: T
  def name: String
  def parse(str: String, indicatorIndex: Map[String, Indicator[_]]): this.type
}

object Indicator {

  trait NumericIndicator[T <: Numeric[T]] {
    def / (rhs: NumericIndicator[T])
  }

  case class QuotientIndicator[T <: Numeric[T]](lhs: NumericIndicator[T], rhs: NumericIndicator[T])
}
