package flashbot.core

import java.util.stream.Collectors

import flashbot.models.Order.Side
import flashbot.models._
import it.unimi.dsi.fastutil.objects.{Object2LongOpenHashMap, ObjectOpenHashSet}

import scala.collection.mutable
import scala.collection.JavaConverters._
import java.lang.Double.NaN
import scala.concurrent.duration._
import scala.language.postfixOps

/**
  * FuzzyBook is a wrapper around an order book that is designed to handle out-of-order data.
  * Configured to only accept data within some time frame. FuzzyBook works best when you send
  * frequent snapshots.
  */
class FuzzyBook(val tickSize: Double,
                val activeTimeRange: Duration = 1 minute) {

  import FuzzyBook._

  private var latestMicros: Long = 0
  private var snapshotMicros: Long = 0
  private var orderBook: OrderBook = new OrderBook(tickSize)
  private val orderTimestamps: Object2LongOpenHashMap[String] = new Object2LongOpenHashMap[String]()
  private val suspectedMissingOrders: ObjectOpenHashSet[String] = new ObjectOpenHashSet[String]()

  /**
    * An event that comes in must be within the active time range.
    */
  def event(micros: Long, e: OrderEvent): FuzzyBook = {
    val errors = e match {
      case ev: OrderOpen =>
        putOrder(e.orderId, micros, ev.side, ev.size, ev.price)
      case ev: OrderChange =>
        putOrder(e.orderId, micros, null, ev.newSize)
      case _: OrderDone =>
        putOrder(e.orderId, micros)
    }

    errors.foreach {
      case OutdatedData(err) => // Ignore outdated data errors
        println("got outdated data", err)

      case err =>
        println(err)
    }

    this
  }

  /**
    * Snapshot tells us that these orders, and only these orders, existed at the given approximate
    * time. We can't rely on the accuracy of the micros snapshot timestamp. The best we can do is
    * detect if we have missed any delete events and delete the lingering orders retroactively.
    * Also, we check if we missed any create events.
    */
  def snapshot(micros: Long, orders: java.util.Set[Order]): FuzzyBook = {

    if (micros < snapshotMicros) {
      println("WARNING: Ignoring outdated snapshot")
      return this
    } else if (micros == snapshotMicros) {
      println("WARNING: Identical snapshot?")
      return this
    }

    // Detect missing orders.
    // These are orders that exist in the snapshot but not in our fuzz.
    orders.forEach(o => {
      if (!orderTimestamps.containsKey(o.id)) {
        // If already in suspected missing orders, just create the order.
        // Otherwise, add it to new suspected.
        if (suspectedMissingOrders.contains(o.id)) {
          putOrder(o.id, micros, o.side, o.amount, o.price.get).foreach(println)
          suspectedMissingOrders.remove(o.id)
        } else {
          suspectedMissingOrders.add(o.id)
        }
      }
    })

    observeTime(null, micros)
    snapshotMicros = micros

    val tsSet = orderTimestamps.asScala.toSet.filter(_._2 > (micros - (10 seconds).toMicros)).map(_._1)
//    println(s"Sanity check, order set: ${orderSet.size}, ts set: ${tsSet.size}")
    val whitelist = orders.asScala.map(_.id) ++ tsSet

    // Check for lingering orders
    (orderBook.orders.keySet.asScala -- whitelist).foreach { id =>
      println(s"Detected lingering order $id. Removing manually.")
      putOrder(id, micros).foreach(println)
    }

    cleanTombstones()

    this
  }

  private def cleanTombstones(): Unit =
    (orderTimestamps.asScala -- orderBook.orders.keySet.asScala)
      .filter(_._2 < latestMicros - (activeTimeRange * 2).toMicros)
      .foreach(x => {
        orderTimestamps.removeLong(x._1)
      })

  // Updates latestMicros if new micros is later than current latest.
  def observeTime(orderId: String, micros: Long): Unit = {
    // Unless the incoming micros is before or equal to the existing order timestamp,
    // update the timestamp.
    if (orderId != null &&
      !(orderTimestamps.containsKey(orderId) &&
        orderTimestamps.getLong(orderId) >= micros)) {
      orderTimestamps.put(orderId, micros)
    }

    if (micros > latestMicros) {
      latestMicros = micros
    }
  }

  /**
    * Idempotent method to set the state of, or delete, an order.
    */
  def putOrder(id: String,
               micros: Long,
               side: Side = null,
               amount: Double = NaN,
               price: Double = NaN,
               silenceUnknown: Boolean = false): Seq[FuzzyError] = {
    if (micros < timeRangeStartMicros) {
      return List(InvalidData(s"Out of time range data ($id, $micros, $price, $amount)"))
    }

    observeTime(id, micros)

    val isUpsert = !java.lang.Double.isNaN(amount)
    val isInsert = !java.lang.Double.isNaN(price) && side != null

    val fe: FuzzyEvent = (isInsert, isUpsert) match {
      case (true, _) => Create
      case (_, true) => Change
      case (false, false) => Delete
    }

    // Implement the state machine transitions for the order.
    (orderState(id), fe) match {

      case (Unknown, Create) =>
        // Inserting a previously unknown order, this is ok.
        // First we try to match the incoming order against the book to generate
        // immediate fills. These fills subtracted from the incoming order and the
        // associated liquidity is also removed from the book.
        val remaining = orderBook.matchMutable(side.flip.toQuote, amount, price)

        // If there were any immediate fills, print them.
        if (remaining < amount) {
          orderBook.foreachMatch { (price, amt) => {
            println(s"Immediate fill: ($price, $amt)")
          }}
        }

        // Open the remaining qty at the limit price.
        if (remaining > 0) {
          orderBook.open(id, price, remaining, side)
        }

        List.empty

      case (Unknown, Change) =>
        if (silenceUnknown) List.empty
        else List(InvalidData(
          s"Got an update event for an unknown order $id at $micros micros."))

      case (Unknown, Delete) =>
        if (silenceUnknown) List.empty
        else List(InvalidData(
          s"Got a delete event for an unknown order $id at $micros micros."))

      case (Active(_, _), Create) =>
        List(InvalidData(
          s"Got a create event an already active order $id at $micros micros."))

      case (Active(time, _), Change) =>
        // Change event for an active order, this is ok, as long as micros is greater than or
        // equal to time.
        if (micros >= time) {
          orderBook.change(id, amount)
          List.empty
        } else
          List(OutdatedData(
            s"Got an outdated update event for active order $id at $micros micros."))

      case (Active(time, _), Delete) =>
        // Delete event for an active order, this is ok, as long as the event is not outdated.
        // Note that this still leaves the order id in orderTimestamps.
        if (micros > time) {
          orderBook.done(id)
          List.empty
        } else List(OutdatedData(
          s"Got an outdated delete event for active order $id at $micros micros."))

      case (Removed(_), Create) =>
        List(InvalidData(s"Got a create event a removed order $id at $micros micros."))

      case (Removed(_), Change) =>
        List(InvalidData(s"Got an update event a removed order $id at $micros micros."))

      case (Removed(_), Delete) =>
        List(InvalidData(s"Got a delete event a removed order $id at $micros micros."))
    }
  }

  def timeRangeStartMicros: Long = latestMicros - activeTimeRange.toMicros

  def orderState(id: String): OrderState = {
    if (orderTimestamps.containsKey(id))
      if (orderBook.orders.containsKey(id))
        Active(orderTimestamps.getLong(id), orderBook.orders(id).amount)
      else Removed(orderTimestamps.getLong(id))
    else Unknown
  }
}

object FuzzyBook {

  sealed trait OrderState
  case object Unknown extends OrderState
  case class Active(micros: Long, size: Double) extends OrderState
  case class Removed(micros: Long) extends OrderState

  sealed trait FuzzyEvent
  case object Create extends FuzzyEvent
  case object Change extends FuzzyEvent
  case object Delete extends FuzzyEvent

  sealed trait FuzzyError
  case class OutdatedData(msg: String) extends FuzzyError
  case class InvalidData(msg: String) extends FuzzyError
}

case class FuzzyOrder()
