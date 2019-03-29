/**
  * Specifies a start and end time in micros. This differs from from/to in DataSelection in that it
  * has no semantics for polling.
  */
@JsonCodec
case class TimeRange(start: Long, end: Long = Long.MaxValue) {
  def startInstant: Instant = Instant.ofEpochMilli(start / 1000)
  def endInstant: Instant = Instant.ofEpochMilli(end / 1000)

  private def roundToSecs(micros: Long): Long = {
    val remainder = micros % 1000000
    (micros - remainder) + (if (remainder > 500000) 1 else 0)
  }

  def roundToSecs: TimeRange = {
    TimeRange(roundToSecs(start), roundToSecs(end))
  }
}
