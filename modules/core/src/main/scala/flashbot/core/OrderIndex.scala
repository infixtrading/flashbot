package flashbot.core

class OrderIndex {
  val byTag = new java.util.HashMap[String, java.util.HashMap[String, OrderRef]]
  val byKey = new java.util.HashMap[String, OrderRef]
  val byClientId = new java.util.HashMap[String, OrderRef]
  var counter: Long = 0

  protected[flashbot] def insert(order: OrderRef): Unit = {
    order.seqNr = counter
    counter += 1
  }

  protected[flashbot] def remove(order: OrderRef): Unit = {
  }
}

