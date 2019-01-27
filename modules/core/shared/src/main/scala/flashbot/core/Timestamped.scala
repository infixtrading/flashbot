package flashbot.core

trait Timestamped {
  def micros: Long
}
object Timestamped {
  val ordering: Ordering[Timestamped] = Ordering.by(_.micros)
}
