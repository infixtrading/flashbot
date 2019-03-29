package flashbot.core

import flashbot.models.core.DataPath

/**
  * Any kind of data that can be streamed into strategies.
  * TODO: Should the type parameter be covariant?
  */
trait MarketData[T] extends Timestamped with SessionInput {
  /**
    * The underlying data instance.
    */
  def data: T

  /**
    * The path by which this market data can be requested. Includes data source, topic, and type.
    * There is no setter for this because, unlike the other fields, it will never change during
    * the processing of a MarketData[T] stream.
    */
  def path: DataPath[T]

  /**
    * Identifies a unique stream of data from the same continuous ingest session.
    */
  def bundle: Long

  /**
    * The index of the item within the bundle.
    */
  def seqid: Long

  /**
    * Returns a new MarketData instance with updated data.
    */
  def withData[B](newData: B, dataType: DataType[B]): MarketData[B]

  /**
    * Returns a new MarketData[T] instance with an updated `micros` field.
    */
  def withMicros(newMicros: Long): MarketData[T]

  /**
    * Returns a new MarketData[T] instance with an updated `bundle` field.
    */
  def withBundle(newBundle: Long): MarketData[T]

  /**
    * Returns a new MarketData[T] instance with an updated `bundle` field.
    */
  def withSeqId(newSeqId: Long): MarketData[T]

}

object MarketData {
  def orderByTime: Ordering[MarketData[_]] = Ordering.by[MarketData[_], Long](_.micros)
  def orderBySequence[T]: Ordering[MarketData[T]] = Ordering.by(x => (x.bundle, x.seqid))

  case class MarketDelta[D](delta: D, micros: Long, bundle: Long)

  implicit def marketDataFmt[T](implicit fmt: DeltaFmt[T]): DeltaFmt[MarketData[T]] =
    new DeltaFmt[MarketData[T]] {
      override type D = MarketDelta[fmt.D]

      override def fmtName = "md." + fmt.fmtName

      override def update(model: MarketData[T], delta: D) = model
        .withData(fmt.update(model.data, delta.delta), model.path.datatype)
        .withBundle(delta.bundle)
        .withMicros(delta.micros)

      override def diff(prev: MarketData[T], current: MarketData[T]) =
        MarketDelta(fmt.diff(prev.data, current.data), current.micros, current.bundle)

      override def fold(x: MarketData[T], y: MarketData[T]) =
        y.withData(fmt.fold(x.data, y.data), y.path.datatype)


      override def unfold(x: MarketData[T]) = fmt.unfold(x.data) match {
        case (first, secondOpt) =>
          val dt = x.path.datatype
          (x.withData(first, dt), secondOpt.map(second => x.withData(second, dt)))
      }
    }

  /**
    * The generic default implementation of MarketData.
    */
  case class BaseMarketData[T](data: T, path: DataPath[T], micros: Long, bundle: Long, seqid: Long)
      extends MarketData[T] {

    override def withMicros(newMicros: Long) = copy(micros = newMicros)
    override def withBundle(newBundle: Long) = copy(bundle = bundle)
    override def withData[B](newData: B, dataType: DataType[B]) =
      copy(data = newData, path = path.withType(dataType))
    override def withSeqId(newSeqId: Long) = copy(seqid = newSeqId)
}

  trait Sequenced {
    def seq: Long
  }

  trait HasProduct {
    def product: String
  }

  implicit class MarketDataOps(md: MarketData[_]) {
    def source = md.path.source
    def topic = md.path.topic
    def dataType = md.path.datatype
  }
}
