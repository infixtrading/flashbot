package com.infixtrading.flashbot.engine

import java.io.File

import de.sciss.fingertree.RangedSeq
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.parser._
import com.infixtrading.flashbot.core.DataSource.{Bundle, SliceIndex}
import com.infixtrading.flashbot.models.core.Slice.SliceId
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.engine.IndexedDeltaLog._
import com.infixtrading.flashbot.engine.TimeLog.ScanDuration
import com.infixtrading.flashbot.models.core.Slice

import scala.collection.immutable.{Queue, SortedSet}
import scala.concurrent.duration._

/**
  * A wrapper around TimeLog that manages continuous bundles of data and stores
  * it efficiently using DeltaFmt. It also takes periodic snapshots and is able
  * to return an index of its data bundles.
  */
class IndexedDeltaLog[T](path: File,
                         retention: Option[Duration],
                         sliceSize: Duration)
                        (implicit fmt: DeltaFmtJson[T]) {

  implicit private val tDe: Decoder[T] = fmt.modelDe
  implicit private val tEn: Encoder[T] = fmt.modelEn
  implicit private val dDe: Decoder[fmt.D] = fmt.deltaDe
  implicit private val dEn: Encoder[fmt.D] = fmt.deltaEn
  implicit val de: Decoder[BundleWrapper] = deriveDecoder
  implicit val en: Encoder[BundleWrapper] = deriveEncoder

  val timeLog = TimeLog[BundleWrapper](path, retention)
  val prevBundleLastItem = timeLog.last
  val currentBundle = prevBundleLastItem.map(_.bundle).getOrElse(-1L) + 1
  var currentSlice = -1
  var lastSeenTime = prevBundleLastItem.map(_.micros).getOrElse(-1L)
  var lastData: Option[T] = None

  def save(micros: Long, data: T): Unit = {
    if (micros < lastSeenTime) {
      throw new RuntimeException("IndexedDeltaLog does not support outdated data.")
    }

    var wrappers = Seq.empty[BundleWrapper]

    // If it's time for a new slice, unfold the data and save snapshot.
    // Also increment the current slice.
    if (lastData.isEmpty || (micros - lastSeenTime) >= sliceSize.toMicros) {
      currentSlice = currentSlice + 1
      val unfolded = FoldFmt.unfoldData(data)
      wrappers ++= unfolded.zipWithIndex.map { case (d, i) =>
        BundleSnap(currentBundle, currentSlice, micros, i == 0, i == unfolded.size - 1,
          Some(lastSeenTime), d.asJson)
      }
    }

    // And now we generate and save a delta against the previous item in this bundle.
    if (lastData.isDefined) {
      val deltas = fmt.diff(lastData.get, data)
      wrappers ++= deltas.map(delta =>
        BundleDelta(currentBundle, currentSlice, micros, delta.asJson))
    }

    // Persist to time log
    wrappers.foreach(timeLog.save(_))

    // Update vars
    lastSeenTime = micros
    lastData = Some(data)
  }


  def scanBundle(from: SliceId,
                 fromMicros: Long = 0,
                 toMicros: Long = Long.MaxValue,
                 polling: Boolean = false): Iterator[(T, Long)] = {

    // Scan from the first snapshot item of this slice until we reach a TimeLog item that
    // is after `toMicros` OR we reach the end of the bundle.
    val wrapperIt = timeLog.scan[(SliceId, Option[SnapBound])](
      (from, Some(Start)),
      b => (b.sliceId, b.matchBound(Start)),
      w => w.micros <= toMicros && w.bundle == from.bundle,
      if (polling) ScanDuration.Continuous else ScanDuration.Finite
    )()(de, sliceBoundOrder)

    // Map from an iterator of wrappers to an iterator of T.
    val dataIt = wrapperIt.scanLeft[(Option[(T, Long)], Boolean)](None, false) {
      /**
        * Base case
        */
      case ((None, false), snap: BundleSnap) =>
        (snap.snap.as[T].toOption.map((_, snap.micros)), snap.isEnd)

      /**
        * Pending
        *
        * There is a partial T and we will use the current BundleSnap to make progress on it.
        */
      case ((Some(partial), false), snap: BundleSnap) =>
        (snap.snap.as[T].toOption.map(item =>
          (fmt.fold(partial._1, item), snap.micros)), snap.isEnd)

      /**
        * Active
        *
        * We have a full T in memory and we're using the current BundleDelta to update it.
        */
      case ((dataOpt, true), delta: BundleDelta) =>
        (dataOpt.map(d =>
          (fmt.update(d._1, delta.delta.as[fmt.D].right.get), delta.micros)), true)
    }

    // Filter incomplete and out of bounds data and return.
    dataIt.filter(_._2).collect {
      case (Some(data), _) => data
    }.filter(d => d._2 >= fromMicros && d._2 <= toMicros)
  }

  def scan(filter: SliceId => Boolean = _ => true,
           fromMicros: Long = 0,
           toMicros: Long = Long.MaxValue,
           polling: Boolean = false): Iterator[Iterator[(T, Long)]] = {

    // Index and filter bundles first using the predicate fn and then the time bounds.
    var sliceIndex = index.filterId(filter).filterOverlaps(fromMicros, toMicros)

    // Map each bundle to an iterator of (T, Long).
    sliceIndex.bundles.values.map(idx =>
      scanBundle(idx.rangedSlices.head.id, fromMicros, toMicros, polling)).toIterator
  }

  def close(): Unit = timeLog.close()

  /**
    * Builds a SliceIndex where the bundle key of each slice is set, but the slice key is a
    * wildcard because. This is due to us not traversing each slice on disk during indexing.
    */
  def index: SliceIndex = {
    var idx = SliceIndex.empty
    var currentSliceHead = timeLog.first

    if (currentSliceHead.isDefined) {
      // We have a slice id, but we're not sure if it represents a full slice.
      // The first slice id we find may belong to a slice that has had it's slice
      // snapshot deleted due to retention, but still has some lingering deltas at
      // the start of the log. Search for the starting and ending snap for the slice.
      val snapStart = findSnapStart(currentSliceHead.get.sliceId)
      val snapEnd = findSnapEnd(currentSliceHead.get.sliceId)

      if (!(snapStart.isDefined && snapEnd.isDefined)) {
        // If the snapshot is not valid, find the next slice id.
        currentSliceHead = findNextSlice(currentSliceHead.get.sliceId)
          .orElse(findNextBundle(currentSliceHead.get.sliceId))
      }
    }

    // Foreach initial slice of each bundle, find the time of the last item of the prev bundle.
    while (currentSliceHead.nonEmpty) {

      // Find the start of the next bundle.
      val nextSliceItem = findNextBundle(currentSliceHead.get.sliceId)

      // If there is no next bundle, then the last item of the log must belong to the
      // current bundle. Grap the very last item, ensure it belongs to the current bundle
      // and populate the index with its time.
      var endTime: Long = -1
      if (nextSliceItem.isDefined) {
        endTime = nextSliceItem.get.prevSliceEndMicros.get
      } else {
        val lastItem = timeLog.last.get
        if (lastItem.bundle != currentSliceHead.get.bundle) {
          throw new RuntimeException(s"Expected bundle id ${currentSliceHead.get.bundle}. " +
            s"Got ${lastItem.bundle}.")
        }
        endTime = lastItem.micros
      }

      // Update the index and currentSliceHead
      idx += Slice(SliceId.wildcard.copy(bundle = currentSliceHead.get.bundle),
        currentSliceHead.get.micros, endTime, None)
      currentSliceHead = nextSliceItem
    }

    idx
  }

  def findSnapStart(id: SliceId): Option[BundleSnap] =
    timeLog.find[(SliceId, Option[SnapBound])](
      (id, Some(Start)), b => (b.sliceId, b.matchBound(Start))
    )(de, sliceBoundOrder).map(_.asInstanceOf[BundleSnap])

  def findSnapEnd(id: SliceId): Option[BundleSnap] =
    timeLog.find[(SliceId, Option[SnapBound])](
      (id, Some(End)), b => (b.sliceId, b.matchBound(End))
    )(de, sliceBoundOrder).map(_.asInstanceOf[BundleSnap])

  def findNextSlice(current: SliceId): Option[BundleSnap] =
    findSnapStart(current.nextSlice)

  def findNextBundle(current: SliceId): Option[BundleSnap] =
    findSnapStart(current.nextBundle)


  def compareSlice(x: SliceId, y: SliceId): Int = {
    if (x.bundle < y.bundle) -1
    else if (x.bundle > y.bundle) 1
    else {
      if (x.slice < y.slice) -1
      else if (x.slice > y.slice) 1
      else 0
    }
  }

  val sliceBoundOrder: Ordering[(SliceId, Option[SnapBound])] =
    new Ordering[(SliceId, Option[SnapBound])] {
      override def compare(x: (SliceId, Option[SnapBound]), y: (SliceId, Option[SnapBound])) = {
        compareSlice(x._1, y._1) match {
          case 0 => (x._2, y._2) match {
            case (None, Some(_)) => 1
            case (Some(a), Some(b)) if a == b => 0
            case (Some(Other), Some(Start)) => 1
            case (Some(Other), Some(End)) => -1
          }
          case other => other
        }
      }
    }

}

object IndexedDeltaLog {

  sealed trait SnapBound
  case object Start extends SnapBound
  case object End extends SnapBound
  case object Other extends SnapBound

  sealed trait BundleWrapper extends Timestamped {
    def bundle: Long
    def slice: Long
    def sliceId: SliceId = SliceId(bundle, slice)

    def matchBound(bound: SnapBound): Option[SnapBound] = {
      (this, bound) match {
        case (bs: BundleSnap, Start) => Some(if (bs.isStart) Start else Other)
        case (bs: BundleSnap, End) => Some(if (bs.isEnd) End else Other)
        case _ => None
      }
    }
  }
  case class BundleSnap(bundle: Long, slice: Long, micros: Long, isStart: Boolean, isEnd: Boolean,
                        prevSliceEndMicros: Option[Long], snap: Json) extends BundleWrapper
  case class BundleDelta(bundle: Long, slice: Long, micros: Long,
                         delta: Json) extends BundleWrapper
}
