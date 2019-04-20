package flashbot.core

/**
  * A lazy hedge can execute at any time, but it won't until you force it to.
  */
class LazyHedge extends OrderRef {

  override def handleSubmit() = ???

  override def handleCancel() = ???

}
