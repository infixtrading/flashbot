package flashbot.core

trait FoldFmt[T] {
  def fold(x: T, y: T): T
  def unfold(x: T): (T, Option[T])
}

object FoldFmt {
  // Implemented as loop because of possible stack size concerns and I'm too
  // lazy to figure out tail call optimization.
  def unfoldData[T](data: T)(implicit fmt: FoldFmt[T]): Seq[T] = {
    var (a: T, b: Option[T]) = fmt.unfold(data)
    var items = Seq(a)
    while (b.isDefined) {
      val ab = fmt.unfold(b.get)
      a = ab._1
      b = ab._2
      items :+= a
    }
    items
  }
}
