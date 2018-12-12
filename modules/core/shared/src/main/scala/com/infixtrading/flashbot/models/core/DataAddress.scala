package com.infixtrading.flashbot.models.core

final case class DataAddress(host: Option[String],
                             path: DataPath) {
  def withHost(host: String): DataAddress = copy(host = Some(host))
  def withHost(hostOpt: Option[String]): DataAddress = copy(host = hostOpt)

  def withTopic(topic: String): DataAddress = copy(path = path.copy(topic = topic))
  def withSource(src: String): DataAddress = copy(path = path.copy(source = src))
  def withType(ty: String): DataAddress = copy(path = path.copy(dataType = ty))
}

object DataAddress {
  def wildcard: DataAddress = DataAddress(None, DataPath.wildcard)
}

