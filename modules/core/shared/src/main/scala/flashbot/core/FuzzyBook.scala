package flashbot.core

import flashbot.models.core.Order.Side
import flashbot.models.core._

import scala.concurrent.duration._

/**
  * FuzzyBook is a wrapper around an order book that is designed to handle out-of-order data.
  * Configured to only accept data within some time frame. FuzzyBook works best when you send
  * frequent snapshots.
  */
case class FuzzyBook(activeTimeRange: Duration = 1 minute,
                     latestMicros: Long = 0,
                     snapshotMicros: Long = 0,
                     orderBook: OrderBook = OrderBook(),
                     orderTimestamps: Map[String, Long] = Map.empty,
                     suspectedMissingOrders: Set[String] = Set.empty) {

  import FuzzyBook._

  /**
    * An event that comes in must be within the active time range.
    */
  def event(micros: Long, e: OrderEvent): FuzzyBook = {
    val (errors, fb) = e match {
      case ev: OrderOpen =>
        putOrder(e.orderId, micros, Some(ev.size), Some(ev.side), Some(ev.price))
      case ev: OrderChange =>
        putOrder(e.orderId, micros, Some(ev.newSize))
      case ev: OrderDone =>
        putOrder(e.orderId, micros)
    }

    errors.foreach {
      case OutdatedData(err) => // Ignore outdated data errors
        println("got outdated data", err)

      case err => println(err)
    }

    fb
  }

  /**
    * Snapshot tells us that these orders, and only these orders, existed at the given approximate
    * time. We can't rely on the accuracy of the micros snapshot timestamp. The best we can do is
    * detect if we have missed any delete events and delete the lingering orders retroactively.
    * Also, we check if we missed any create events.
    */
  def snapshot(micros: Long, orders: Set[Order]): FuzzyBook = {
    val ordersMap = orders.map(o => (o.id, o)).toMap

    if (micros < snapshotMicros) {
      println("WARNING: Ignoring outdated snapshot")
      return this
    } else if (micros == snapshotMicros) {
      println("WARNING: Identical snapshot?")
      return this
    }

    val isFirstSnapshot = snapshotMicros == 0
    val orderSet = orders.map(_.id).toSet

    var result = copy(
      latestMicros = math.max(latestMicros, micros),
      snapshotMicros = micros
    )

    // Check for missed orders. We require two consecutive snapshots to confirm their suspicion
    // of a missing order. On the second confirmation, we add the order to the book.
    val existingMissing = suspectedMissingOrders
    val newMissing = orderSet -- result.orderTimestamps.keySet
    result = result.copy(suspectedMissingOrders = newMissing)
    (existingMissing intersect newMissing).foreach { id =>
//      println(s"Detected missed order $id. Adding manually.")
      result = result.putOrderLogErrs(id, micros,
        Some(ordersMap(id).amount), Some(ordersMap(id).side), ordersMap(id).price)
    }

    val tsSet =
      result.orderTimestamps.toSet.filter(_._2 > (micros - (10 seconds).toMicros)).map(_._1)
//    println(s"Sanity check, order set: ${orderSet.size}, ts set: ${tsSet.size}")
    val whitelist = orderSet ++ tsSet

    // Check for lingering orders
    (result.orderBook.orders.keySet -- whitelist).foreach { id =>
//      println(s"Detected lingering order $id. Removing manually.")
      result = result.putOrderLogErrs(id, micros)
    }

    result.cleanTombstones
//    result
  }

  def cleanTombstones: FuzzyBook = {
    val blacklist = (orderTimestamps -- orderBook.orders.keySet)
      .filter(_._2 < latestMicros - (2 minutes).toMicros).keySet
    copy( orderTimestamps = orderTimestamps -- blacklist )
  }

  def putOrderLogErrs(id: String,
                      micros: Long,
                      amount: Option[Double] = None,
                      side: Option[Side] = None,
                      price: Option[Double] = None,
                      silenceUnknown: Boolean = false): FuzzyBook = {
    val (errs, ret) = putOrder(id, micros, amount, side, price, silenceUnknown)
    errs.foreach(println)
    ret
  }

  /**
    * Idempotent method to set the state of, or delete, an order.
    */
  def putOrder(id: String,
               micros: Long,
               amount: Option[Double] = None,
               side: Option[Side] = None,
               price: Option[Double] = None,
               silenceUnknown: Boolean = false): (Seq[FuzzyError], FuzzyBook) = {
    if (micros < timeRangeStartMicros) {
      return (Seq(InvalidData(s"Out of time range data ($id, $micros, $price, $amount)")), this)
    }

    val isUpsert = amount.isDefined
    val isInsert = price.isDefined && side.isDefined

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
        val (immediateFills, newBook) = orderBook.fill(side.get, amount.get, price)
        val amountFilled = immediateFills.map(_._2).sum

        if (immediateFills.nonEmpty) {
          println("IMMEDIATE FILLS")
          println(immediateFills)
        }

        (Seq.empty, copy(
          latestMicros = math.max(latestMicros, micros),
          orderBook =
            if (amountFilled >= amount.get) newBook
            else newBook.open(id, price.get, amount.get - amountFilled, side.get),
          orderTimestamps = orderTimestamps + (id -> micros)
        ))
      case (Unknown, Change) =>
        (if (silenceUnknown) Seq.empty
          else Seq(InvalidData(
              s"Got an update event for an unknown order $id at $micros micros.")), this)

      case (Unknown, Delete) =>
        (if (silenceUnknown) Seq.empty
        else Seq(InvalidData(
          s"Got a delete event for an unknown order $id at $micros micros.")),

          // Set the state to "removed"
          copy(orderTimestamps = orderTimestamps.updated(id, micros)))

      case (Active(time, size), Create) =>
        (Seq(InvalidData(
          s"Got a create event an already active order $id at $micros micros.")), this)

      case (Active(time, size), Change) =>
        // Change event for an active order, this is ok, as long as micros is greater than or
        // equal to time. Warn if it's equal
        if (micros > time)
          (Seq.empty, copy(
            latestMicros = math.max(latestMicros, micros),
            orderBook = orderBook._change(id, amount.get),
            orderTimestamps = orderTimestamps + (id -> orderTimestamps(id))
          ))
        else
          (Seq(OutdatedData(
            s"Got an outdated update event for active order $id at $micros micros.")), this)

      case (Active(time, size), Delete) =>
        // Delete event for an active order, this is ok, as long as the event is not outdated.
        // Note that this still leaves the order id in orderTimestamps.
        if (micros > time)
          (Seq.empty, copy(
            latestMicros = math.max(latestMicros, micros),
            orderBook = orderBook._done(id)
          ))
        else (Seq(OutdatedData(
          s"Got an outdated delete event for active order $id at $micros micros.")), this)

      case (Removed(time), Create) =>
        (Seq(InvalidData(
          s"Got a create event a removed order $id at $micros micros.")), this)
      case (Removed(time), Change) =>
        (Seq(InvalidData(
          s"Got an update event a removed order $id at $micros micros.")), this)
      case (Removed(time), Delete) =>
        (Seq(InvalidData(
          s"Got a delete event a removed order $id at $micros micros.")), this)
    }
  }

  def timeRangeStartMicros: Long = latestMicros - activeTimeRange.toMicros

  def orderState(id: String): OrderState = {
    val time = orderTimestamps.get(id)
    if (time.isDefined)
      if (orderBook.orders.isDefinedAt(id))
        Active(time.get, orderBook.orders(id).amount)
      else Removed(time.get)
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
