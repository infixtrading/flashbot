package flashbot.engine

import flashbot.models.core.OrderOpen

//import akka.actor.Actor
//import akka.event.Logging
//
//import scala.collection.immutable.Queue
//import scala.concurrent.{ExecutionContext, Future}
//import scala.util.{Failure, Success}
//
object OrderBookProvider {
  sealed trait BookActorCmd
  final case class Snapshot(product: String,
                            snap: (Long, Seq[OrderOpen])) extends BookActorCmd
  final case class Subscribe(products: Set[String]) extends BookActorCmd
}
//
//abstract class OrderBookProvider[E <: RawOrderEvent]
//    (source: String, updateFn: OrderBookMD[E] => Unit) extends Actor {
//  import OrderBookProvider._
//
//  implicit val ec: ExecutionContext = context.dispatcher
//
//  def getSnapshot(product: String, first: E): Future[(Long, Seq[SnapshotOrder])]
//  def ingest(ps: Set[String], onEvent: E => Unit): Unit
//
//  val log = Logging(context.system, this)
//  var states: Map[String, OrderBookMD[E]] = Map.empty
//  var events: Map[String, Queue[E]] = Map.empty
//  var seenProducts: Set[String] = Set.empty
//  var initialized: Boolean = false
//
//  override def receive: Receive = {
//    case Subscribe(ps) =>
//      log.info(s"SUBSCRIBE $ps")
//      // Just to be safe. You can only subscribe once.
//      if (initialized)
//        crash("MarketData already initialized")
//      initialized = true
//
//      // Initialize market states for each product
//      ps.foreach({ p =>
//        states = states + (p -> OrderBookMD[E](source, p.toString))
//        events = events + (p -> Queue.empty)
//      })
//
//      // Subscribe to all products and send events to self
//      ingest(ps, self ! _)
//
//    case Snapshot(p, (seq, orders)) =>
//      states = states + (p -> states(p).addSnapshot(seq, orders))
//      println("got snapshot", p, seq, orders.length)
//
//      // Go through all events and apply them if their seq # is greater than that of the snapshot
//      while (events(p).nonEmpty)
//        events(p).dequeue match {
//          case (event, q) =>
//            events = events + (p -> q)
//            processEvent(event)
//        }
//
//    case ev: E =>
//      val e = ev.toOrderEvent
//      // We wait until we receive the first event for the product to request the snapshot.
//      // That way there's no risk of missing events between the snapshot and the first event.
//      if (!(seenProducts contains e.product)) {
//        getSnapshot(e.product, ev) onComplete {
//          case Success(snapshot) => self ! Snapshot(e.product, snapshot)
//          case Failure(err) => throw err
//        }
//        seenProducts = seenProducts + e.product
//      }
//
//      if (!stateIsReady(states(e.product)))
//        events = events + (e.product -> events(e.product).enqueue(ev))
//      else
//        processEvent(ev)
//  }
//
//  def processEvent(event: E): Unit = {
//    val e = event.toOrderEvent
//    if (event.seq >= states(e.product).seq) {
//      states = states + (e.product -> states(e.product).addEvent(event))
//      updateFn(states(e.product))
//    }
//  }
//
//  def stateIsReady(state: OrderBookMD[E]): Boolean =
//    state.seq != 0 && state.data.isInitialized
//
//  def crash(err: String): Unit = {
//    log.error(err)
//    throw new RuntimeException(err)
//  }
//}
//
