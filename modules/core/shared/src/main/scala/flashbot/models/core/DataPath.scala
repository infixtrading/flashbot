package flashbot.models.core

import flashbot.core.DataType.AnyType
import flashbot.core.{DataType, DeltaFmtJson}
import io.circe.{Decoder, Encoder}

case class DataPath[+T](source: String, topic: String, datatype: DataType[T]) {
  override def toString: String = List(source, topic, datatype.toString).mkString("/")

  def matches(matcher: DataPath[_]): Boolean = {
    this._matches(matcher) || matcher._matches(this)
  }

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

  // Returns this path as the type of the pattern path if it matches.
  def filter[F](pattern: DataPath[F]): Option[DataPath[F]] =
    if (_matches(pattern)) Some(this.asInstanceOf[DataPath[F]])
    else None

}

object DataPath {
  implicit def parse[T](path: String): DataPath[T] = path.split("/").toList match {
    case srcKey :: topic :: dataTypeStr :: Nil =>
      DataPath(srcKey, topic, DataType.parse(dataTypeStr).get.asInstanceOf[DataType[T]])
  }

  def apply[T](str: String): DataPath[T] = parse(str)

  def wildcard: DataPath[Any] = DataPath("*", "*", AnyType)

  implicit def dataPathToString[T](path: DataPath[T]): String =
    List(path.source, path.topic, path.datatype).mkString("/")

  implicit def pathEncoder[T]: Encoder[DataPath[T]] = Encoder.encodeString.contramap(_.toString)
  implicit def pathDecoder[T]: Decoder[DataPath[T]] = Decoder.decodeString.map(parse(_).asInstanceOf[DataPath[T]])
}
