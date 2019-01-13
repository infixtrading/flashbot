package com.infixtrading.flashbot.models.core

final case class DataPath(source: String, topic: String, datatype: String) {
  override def toString: String = List(source, topic, datatype).mkString("/")

  def matches(matcher: DataPath): Boolean = {
    this._matches(matcher) || matcher._matches(this)
  }

  def _matches(matcher: DataPath): Boolean = {
    def matchItem(value: String, pattern: String) = pattern == "*" || value == pattern
    val srcMatches = matchItem(source, matcher.source)
    val topicMatches = matchItem(topic, matcher.topic)
    val typeMatches = matchItem(datatype, matcher.datatype)
    srcMatches && topicMatches && typeMatches
  }

  def topicValue: Option[String] = if (topic == "*") None else Some(topic)
  def sourceValue: Option[String] = if (source == "*") None else Some(source)
  def typeValue: Option[String] = if (datatype == "*") None else Some(datatype)

  def value: Option[DataPath] = (topicValue, sourceValue, typeValue) match {
    case (Some(top), Some(src), Some(tpe)) => Some(this)
    case _ => None
  }
}

object DataPath {
  implicit def parse(path: String): DataPath = path.split("/").toList match {
    case srcKey :: topic :: dataType :: Nil => DataPath(srcKey, topic, dataType)
  }

  def wildcard: DataPath = "*/*/*"
}
