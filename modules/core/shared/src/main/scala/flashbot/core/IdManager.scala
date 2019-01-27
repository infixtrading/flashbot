package flashbot.core

/**
  * Manages the relationships of the three types of order ids in our system:
  * Actual ids, client ids, target ids.
  */
case class IdManager(clientToTarget: Map[String, TargetId] = Map.empty,
                     targetToActual: Map[TargetId, String] = Map.empty,
                     actualToTarget: Map[String, TargetId] = Map.empty) {

  def initCreateOrderId(targetId: TargetId, clientId: String): IdManager =
    copy(clientToTarget = clientToTarget + (clientId -> targetId))

  def receivedOrderId(clientId: String, actualId: String): IdManager = copy(
    targetToActual = targetToActual + (clientToTarget(clientId) -> actualId),
    actualToTarget = actualToTarget + (actualId -> clientToTarget(clientId)),
    clientToTarget = clientToTarget - clientId
  )

  def orderIdComplete(actualId: String): IdManager = copy(
    targetToActual = targetToActual - actualToTarget(actualId),
    actualToTarget = actualToTarget - actualId
  )

  def actualIdForTargetId(targetId: TargetId): String = targetToActual(targetId)
}
