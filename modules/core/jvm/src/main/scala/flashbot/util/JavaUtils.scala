package flashbot.util

import java.util
import java.util.concurrent.{CompletableFuture, CompletionStage}

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters

/**
  * Utilities for converting Scala collections and domain objects to their Java equivalent.
  */
object JavaUtils {
  def fromJava[T](future: CompletableFuture[T]): scala.concurrent.Future[T] =
    FutureConverters.toScala(future)
  def toJava[T](future: scala.concurrent.Future[T]): CompletableFuture[T] =
    FutureConverters.toJava(future).toCompletableFuture

//  def fromJava[T](list: java.util.List[T]):

  def toJava[T](set: Set[T]): java.util.Set[T] = set.asJava

  def getOrCompute[K, V](map: java.util.HashMap[K, V], key: K, default: => V): V = {
    var value = map.get(key)
    if (value == null) {
      value = default
      map.put(key, value)
    }
    value
  }

  def getOrCompute[K, V](map: java.util.HashMap[K, java.util.HashMap[K, V]],
                         key1: K, key2: K, default: => V): V = {
    val subMap = getOrCompute[K, java.util.HashMap[K, V]](map, key1, new java.util.HashMap[K, V]())
    getOrCompute[K, V](subMap, key2, default)
  }

  def hashMap2d[K, V]: java.util.HashMap[K, java.util.HashMap[K, V]] =
    new java.util.HashMap[K, java.util.HashMap[K, V]]()

}

