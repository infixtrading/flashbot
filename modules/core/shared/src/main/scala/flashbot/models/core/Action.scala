package flashbot.models.core

import flashbot.core.TargetId
import flashbot.models.core.Order._

import scala.collection.immutable.Queue

sealed trait Action {
  def targetId: TargetId
  def getSize: Option[BigDecimal]
}

object Action {

  case class PostMarketOrder(id: String, targetId: TargetId, side: Side,
                             size: Option[BigDecimal],
                             funds: Option[BigDecimal]) extends Action {
    override def getSize = size
  }
  case class PostLimitOrder(id: String, targetId: TargetId, side: Side,
                            size: BigDecimal, price: BigDecimal, postOnly: Boolean) extends Action {
    override def getSize = Some(size)
  }
  case class CancelLimitOrder(targetId: TargetId) extends Action {
    override def getSize = None
  }

  case class ActionQueue(active: Option[Action] = None, queue: Queue[Action] = Queue.empty) {
    def enqueue(action: Action): ActionQueue = copy(queue = queue.enqueue(action))
    def enqueue(actions: Seq[Action]): ActionQueue =
      actions.foldLeft(this) { (memo, action) => memo.enqueue(action) }
    def closeActive: ActionQueue = active match {
      case Some(_) => copy(active = None)
    }
    def isEmpty: Boolean = active.isEmpty && queue.isEmpty
    def nonEmpty: Boolean = !isEmpty
  }
}
