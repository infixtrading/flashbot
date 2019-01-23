package com.infixtrading.flashbot.models.core

final case class DataAddress[T](host: Option[String],
                                path: DataPath[T]) {
  def withHost(host: String): DataAddress[T] = copy(host = Some(host))
  def withHost(hostOpt: Option[String]): DataAddress[T] = copy(host = hostOpt)

  def withTopic(topic: String): DataAddress[T] = copy(path = path.copy(topic = topic))
  def withSource(src: String): DataAddress[T] = copy(path = path.copy(source = src))
//  def withType(ty: String): DataAddress[T] = copy(path = path.copy(datatype = ty))
}

object DataAddress {
//  def wildcard: DataAddress = DataAddress(None, DataPath.wildcard)
}

