package flashbot.server

import de.sciss.fingertree.RangedSeq
import flashbot.models.core.Slice
import flashbot.models.core.Slice.SliceId

object Slices {

  implicit class SliceIndex[T](val rangedSlices: RangedSeq[Slice[T], Long])
    extends AnyVal with Mergable[SliceIndex[T]] {

    override def merge(other: SliceIndex[T]) = other.rangedSlices

    def bundleId: Long = rangedSlices.head.id.bundle
    def filterOverlaps(from: Long, to: Long): SliceIndex[T] = rangedSlices.filterOverlaps((from, to)).toSeq
    def slices: Seq[Slice[T]] = rangedSlices.filterOverlaps((0, Long.MaxValue)).toSeq
    def filterId(fn: SliceId => Boolean): SliceIndex[T] = slices.filter(s => fn(s.id))
    def isEmpty: Boolean = slices.isEmpty
    def startMicros: Long = rangedSlices.head.fromMicros
    def endMicros: Long = rangedSlices.last.toMicros

    def +(slice: Slice[T]): SliceIndex[T] = rangedSlices + slice

    def bundles: Map[Long, SliceIndex[T]] =
      slices.groupBy(_.id.bundleValue.get).mapValues(SliceIndex.build)
  }
  object SliceIndex {
    def empty[T]: SliceIndex[T] = Seq.empty

    implicit def build[T](seq: Seq[Slice[T]]): SliceIndex[T] = new SliceIndex(
      RangedSeq[Slice[T], Long](seq: _*)(
        slice => (slice.fromMicros, slice.toMicros),
        Ordering[Long]
      )
    )
  }

  trait Mergable[T] extends Any {
    def merge(other: T): T
  }
}
