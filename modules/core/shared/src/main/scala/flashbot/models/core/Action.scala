package flashbot.models.core

import flashbot.core.TargetId
import flashbot.models.core.Order._

import scala.collection.immutable.Queue

sealed trait Action {
  def targetId: TargetId
}

object Action {

  case class PostMarketOrder(id: String, targetId: TargetId, side: Side,
                             size: Option[Double], funds: Option[Double]) extends Action
  case class PostLimitOrder(id: String, targetId: TargetId, side: Side,
                            size: Double, price: Double, postOnly: Boolean) extends Action
  case class CancelLimitOrder(targetId: TargetId) extends Action

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

  //  trait ActionResponse
  //  case object Ok extends ActionResponse
  //  // TODO: Add more specific failures and handle them (such as rate limit retries)
  //  case object Fail extends ActionResponse
}
