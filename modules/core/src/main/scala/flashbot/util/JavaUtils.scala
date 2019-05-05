package flashbot.util

import java.util.concurrent.CompletableFuture

import scala.compat.java8.FutureConverters

/**
  * Utilities for converting Scala collections and domain objects to their Java equivalent.
  */
object JavaUtils {
  def fromJava[T](future: CompletableFuture[T]): scala.concurrent.Future[T] =
    FutureConverters.toScala(future)
  def toJava[T](future: scala.concurrent.Future[T]): CompletableFuture[T] =
    FutureConverters.toJava(future).toCompletableFuture

//  def toJava[T](set: Set[T]): java.util.Set[T] = set.asJava

}
