package com.infixtrading.flashbot.models.core
import com.infixtrading.flashbot.models.core.Slice.SliceId

/**
  * A description of a continuous set of data.
  *
  * @param id the slice id pattern that matches the data in this slice.
  * @param fromMicros inclusive lower time bound
  * @param toMicros exclusive upper time bound
  * @param address optional address to give context to this slice
  */
case class Slice[T](id: SliceId, fromMicros: Long, toMicros: Long, address: Option[DataAddress[T]]) {
  def typeValue: Option[String] = address.flatMap(_.path.typeValue).map(_.toString)
  def topicValue: Option[String] = address.flatMap(_.path.topicValue)
  def sourceValue: Option[String] = address.flatMap(_.path.sourceValue)

  def mapAddress(fn: DataAddress[T] => DataAddress[T]): Slice[T] = copy(address = address.map(fn))
}

object Slice {

  case class SliceId(bundle: Long, slice: Long) {

    import SliceId._

    def nextSlice: SliceId = SliceId(bundle, slice + 1)

    def nextBundle: SliceId = SliceId(bundle + 1, 0)

    def bundleValue: Option[Long] = if (bundle == wildcard.bundle) None else Some(bundle)

    def sliceValue: Option[Long] = if (slice == wildcard.slice) None else Some(slice)

    def value: Option[SliceId] = (bundleValue, sliceValue) match {
      case (Some(b), Some(s)) => Some(SliceId(b, s))
      case _ => None
    }

    def matches(sliceId: SliceId): Boolean = {
      assert(value.isDefined || sliceId.value.isDefined,
        "At least one concrete, non-wildcard SliceId is required for matching.")

      val bundleMatches = (bundleValue, sliceId.bundleValue) match {
        // Either they are both concrete vals, in which case test for equality.
        case (Some(a), Some(b)) => a == b
        // Or one of them is a wildcard, in which case it must be a match.
        case _ => true
      }
      // Same as bundle matching
      val sliceMatches = (sliceValue, sliceId.sliceValue) match {
        case (Some(a), Some(b)) => a == b
        case _ => true
      }
      // Both fields must match
      bundleMatches && sliceMatches
    }
  }

  object SliceId {
    def wildcard: SliceId = SliceId(-1, -1)
  }

}
