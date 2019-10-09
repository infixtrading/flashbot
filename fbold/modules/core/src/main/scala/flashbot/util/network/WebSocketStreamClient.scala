package flashbot.util.network

import java.net.URI
import java.nio.ByteBuffer

import akka.actor.{ActorRef, PoisonPill}
import akka.event.LoggingAdapter
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake

/**
  * A thin wrapper around WebSocketClient that catches message handling exceptions and closes
  * the stream via it's actor ref no error.
  */
abstract class WebSocketStreamClient(uri: URI) extends WebSocketClient(uri) {

  def name: String
  def sourceRef: ActorRef
  def log: LoggingAdapter

  def onOpenSafe(handshake: ServerHandshake): Unit = {}
  def onMessageSafe(message: String): Unit = {}
  def onCloseSafe(code: Int, reason: String, remote: Boolean): Unit = {}
  def onErrorSafe(ex: Exception): Unit = {}

  final override def onOpen(handshakedata: ServerHandshake): Unit = {
    log.info("{} WebSocket open", name)
    callSafe(onOpenSafe(handshakedata))
  }

  final override def onMessage(message: String): Unit = {
    callSafe(onMessageSafe(message))
  }

  final override def onClose(code: Int, reason: String, remote: Boolean): Unit = {
    log.info("{} WebSocket closed", name)
    sourceRef ! PoisonPill
    callSafe(onCloseSafe(code, reason, remote))
  }

  final override def onError(ex: Exception): Unit = {
    log.error(ex, "Error in {} WebSocket", name)
    sourceRef ! PoisonPill
    callSafe(onErrorSafe(ex))
  }

  private def callSafe(expr: =>Unit): Unit = {
    try {
      expr
    } catch {
      case err: Throwable =>
        log.error(err, s"Error in $name WebSocketStreamClient event handler")
        sourceRef ! PoisonPill
    }
  }

}
