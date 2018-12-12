package com.infixtrading.flashbot.core

/**
  * Any kind of data that can be streamed into strategies.
  */
trait MarketData[T] extends Timestamped {
  /**
    * The underlying data instance.
    */
  def data: T

  /**
    * The path by which this market data can be requested. Includes data source, topic, and type.
    * There is no setter for this because, unlike the other fields, it will never change during
    * the processing of a MarketData[T] stream.
    */
  def path: DataPath

  /**
    * MarketData objects are considered to be from the same continuous data bundle iff they
    * have the same address AND their bundleIndex is the same.
    */
  def bundleIndex: Int

  /**
    * Returns a new MarketData[T] instance with updated data.
    */
  def withData(newData: T): MarketData[T]

  /**
    * Returns a new MarketData[T] instance with an updated `micros` field.
    */
  def withMicros(newMicros: Long): MarketData[T]

  /**
    * Returns a new MarketData[T] instance with an updated `bundleIndex` field.
    */
  def withBundle(newBundle: Int): MarketData[T]
}

object MarketData {
  implicit val ordering: Ordering[MarketData[_]] = Ordering.by(_.micros)

  case class MarketDelta[D](delta: D, micros: Long, bundle: Int)

  implicit def marketDataFmt[T](implicit fmt: DeltaFmt[T]): DeltaFmt[MarketData[T]] =
    new DeltaFmt[MarketData[T]] {
      override type D = MarketDelta[fmt.D]

      override def fmtName = "md." + fmt.fmtName

      override def update(model: MarketData[T], delta: D) = model
        .withData(fmt.update(model.data, delta.delta))
        .withBundle(delta.bundle)
        .withMicros(delta.micros)

      override def diff(prev: MarketData[T], current: MarketData[T]) =
        fmt.diff(prev.data, current.data).map(MarketDelta(_, current.micros, current.bundleIndex))

      override def fold(x: MarketData[T], y: MarketData[T]) =
        y.withData(fmt.fold(x.data, y.data))


      override def unfold(x: MarketData[T]) = fmt.unfold(x.data) match {
        case (first, secondOpt) =>
          (x.withData(first), secondOpt.map(second => x.withData(second)))
      }
    }

  /**
    * The generic default implementation of MarketData.
    */
  case class BaseMarketData[T](data: T, path: DataPath, micros: Long, bundleIndex: Int)
      extends MarketData[T] {

    override def withMicros(newMicros: Long) = copy(micros = newMicros)
    override def withBundle(newBundle: Int) = copy(bundleIndex = bundleIndex)
    override def withData(newData: T) = copy(data = newData)
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
    def dataType = md.path.dataType
  }
}
