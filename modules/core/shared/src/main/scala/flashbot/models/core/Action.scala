package flashbot.models.core

import flashbot.core.TargetId
import flashbot.models.core.Order._

import scala.collection.immutable.Queue

sealed trait Action {
  def targetId: TargetId
  def getSize: Option[Double]
}

object Action {

  case class PostMarketOrder(id: String, targetId: TargetId, side: Side,
                             size: Option[Double], funds: Option[Double]) extends Action {
    override def getSize = size
  }
  case class PostLimitOrder(id: String, targetId: TargetId, side: Side,
                            size: Double, price: Double, postOnly: Boolean) extends Action {
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
