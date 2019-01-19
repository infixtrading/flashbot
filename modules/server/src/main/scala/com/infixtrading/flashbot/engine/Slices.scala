package com.infixtrading.flashbot.engine

import com.infixtrading.flashbot.models.core.Slice
import com.infixtrading.flashbot.models.core.Slice.SliceId
import de.sciss.fingertree.RangedSeq

object Slices {

  implicit class SliceIndex(val rangedSlices: RangedSeq[Slice, Long])
    extends AnyVal with Mergable[SliceIndex] {

    override def merge(other: SliceIndex) = other.rangedSlices

    def bundleId: Long = rangedSlices.head.id.bundle
    def filterOverlaps(from: Long, to: Long): SliceIndex = rangedSlices.filterOverlaps((from, to)).toSeq
    def slices: Seq[Slice] = rangedSlices.filterOverlaps((0, Long.MaxValue)).toSeq
    def filterId(fn: SliceId => Boolean): SliceIndex = slices.filter(s => fn(s.id))
    def isEmpty: Boolean = slices.isEmpty
    def startMicros: Long = rangedSlices.head.fromMicros
    def endMicros: Long = rangedSlices.last.toMicros

    def +(slice: Slice): SliceIndex = rangedSlices + slice

    def bundles: Map[Long, SliceIndex] =
      slices.groupBy(_.id.bundleValue.get).mapValues(SliceIndex.build)
  }
  object SliceIndex {
    def empty: SliceIndex = Seq.empty

    implicit def build(seq: Seq[Slice]): SliceIndex = new SliceIndex(
      RangedSeq[Slice, Long](seq: _*)(
        slice => (slice.fromMicros, slice.toMicros),
        Ordering[Long]
      )
    )
  }

  trait Mergable[T] extends Any {
    def merge(other: T): T
  }
}
