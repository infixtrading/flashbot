package flashbot.util

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
}

