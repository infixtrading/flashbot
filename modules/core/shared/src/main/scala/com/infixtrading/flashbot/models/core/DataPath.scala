package com.infixtrading.flashbot.models.core

final case class DataPath(source: String, topic: String, dataType: String) {
  override def toString: String = List(source, topic, dataType).mkString("/")

  def matches(matcher: DataPath): Boolean = {
    def matches(value: String, pattern: String) = pattern == "*" || value == pattern
    val srcMatches = matches(source, matcher.source)
    val topicMatches = matches(topic, matcher.topic)
    val typeMatches = matches(dataType, matcher.dataType)
    (srcMatches && topicMatches && typeMatches) || matcher.matches(this)
  }

  def topicValue: Option[String] = if (topic == "*") None else Some(topic)
  def sourceValue: Option[String] = if (source == "*") None else Some(source)
  def typeValue: Option[String] = if (dataType == "*") None else Some(dataType)

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
