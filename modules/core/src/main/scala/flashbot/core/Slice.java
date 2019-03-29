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
