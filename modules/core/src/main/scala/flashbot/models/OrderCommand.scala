package flashbot.models

import flashbot.models.Order.Side

import scala.collection.mutable

sealed trait OrderCommand {
  def id: String
}

object OrderCommand {

  sealed trait PostOrderCommand extends OrderCommand
  case class PostMarketOrder(id: String, side: Side, size: Double) extends PostOrderCommand
  case class PostLimitOrder(id: String, side: Side, size: Double,
                            price: Double, postOnly: Boolean) extends PostOrderCommand

  class CommandQueue {
    private var active: Option[OrderCommand] = None
    private var queue: mutable.Queue[OrderCommand] = mutable.Queue.empty[OrderCommand]

    def enqueue(cmd: OrderCommand): Unit = queue.enqueue(cmd)
    def closeActive(): Unit = {
      active = None
    }

    def isEmpty: Boolean = active.isEmpty && queue.isEmpty
    def nonEmpty: Boolean = !isEmpty

    def activateNext(): Option[OrderCommand] = {
      if (active.isDefined) None
      else if (queue.isEmpty) None
      else {
        val item = queue.dequeue()
        active = Some(item)
        Some(item)
      }
    }

    def closeActiveForOrderId(id: String): Unit = {

    }
  }
}
