package flashbot.core

case class DataPath[+T](source: String, topic: String, datatype: DataType[T]) {
  override def toString: String = List(source, topic, datatype.toString).mkString("/")

  def matches(matcher: DataPath[_]): Boolean = {
    this._matches(matcher) || matcher._matches(this)
  }

  def matchesLocation(matcher: DataPath[_]): Boolean = matches(matcher.withoutType)

  def _matches(matcher: DataPath[_]): Boolean = {
    def matchItem(value: String, pattern: String) = pattern == "*" || value == pattern
    val srcMatches = matchItem(source, matcher.source)
    val topicMatches = matchItem(topic, matcher.topic)
    val typeMatches = matcher.datatype == AnyType || matcher.datatype == datatype
    srcMatches && topicMatches && typeMatches
  }

  def topicValue: Option[String] = if (topic == "*") None else Some(topic)
  def sourceValue: Option[String] = if (source == "*") None else Some(source)
  def typeValue[S >: T]: Option[DataType[S]] = if (datatype == AnyType) None else Some(datatype)

  def value: Option[DataPath[T]] = (topicValue, sourceValue, typeValue) match {
    case (Some(_), Some(_), Some(_)) => Some(this)
    case _ => None
  }

  def isPattern: Boolean = value.isEmpty

  def fmt[S >: T]: DeltaFmtJson[S] = datatype.fmtJson

  def withType[D](dt: DataType[D]): DataPath[D] = copy(datatype = dt)

  def withoutType: DataPath[Any] = copy(datatype = AnyType)

  def withMarket(market: Market): DataPath[T] =
    copy(source = market.exchange, topic = market.symbol)

  // Returns this path as the type of the pattern path if it matches.
  def filter[F](pattern: DataPath[F]): Option[DataPath[F]] =
    if (_matches(pattern)) Some(this.asInstanceOf[DataPath[F]])
    else None

  def market: Market = Market(source, topic)
}
