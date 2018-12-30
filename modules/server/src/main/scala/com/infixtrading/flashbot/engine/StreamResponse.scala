package com.infixtrading.flashbot.engine

import akka.NotUsed
import akka.actor.{ActorContext, ActorRef, RootActorPath}
import akka.stream.{Materializer, SourceRef}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.pattern.pipe
import com.infixtrading.flashbot.core.DeltaFmt
import com.infixtrading.flashbot.util.stream._
import com.infixtrading.flashbot.engine.CompressedSourceRef._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Wraps streams in various formats and shapes so that we can pass easily them around the cluster.
  */
sealed trait StreamResponse[T] {
  import StreamResponse._
  implicit def fmt: DeltaFmt[T]

  /**
    * When we already have a StreamResponse in some actor, and we're sending it over to some
    * other one. I.e. Proxying one, not building it from scratch. Here we ensure that a
    * MemorySource is never sent over the network. We do not convert in the opposite situation.
    * So if `rsp` is already a NetworkSource, but we happen to be sending it over to a local
    * actor, we just leave it as-is.
    */
  def rebuild(recipient: ActorRef)
             (implicit ctx: ActorContext, mat: Materializer,
              ec: ExecutionContext): Future[StreamResponse[T]] = {
    (actorIsLocal(recipient), this) match {
      case (false, MemorySource(_: DeltaFmt[T], stream)) =>
        build(stream, recipient)
      case (false, SourceRspList(f: DeltaFmt[T], responses)) =>
        Future.sequence(responses.map(_.rebuild(recipient))).map(SourceRspList(f, _))
      case _ => Future.successful(this)
    }
  }

  def rebuild(implicit ctx: ActorContext, mat: Materializer,
              ec: ExecutionContext): Future[StreamResponse[T]] = rebuild(ctx.sender)

  def toSource: Source[T, NotUsed] = this match {
    case MemorySource(fmt, stream) => stream
    case NetworkSource(fmt, ref: CompressedSourceRef[T]) =>
      decompressStream(ref.fmt, ref.compressedStreamRef.source)
    case SourceRspList(fmt, responses) =>
      Source(responses.toIndexedSeq).flatMapConcat(_.toSource)
  }
}

case class MemorySource[T](fmt: DeltaFmt[T],
                           stream: Source[T, NotUsed]) extends StreamResponse[T]

case class NetworkSource[T](fmt: DeltaFmt[T],
                            compressedRef: CompressedSourceRef[T]) extends StreamResponse[T]

object NetworkSource {
  def build[T](src: Source[T, NotUsed])
              (implicit fmt: DeltaFmt[T], mat: Materializer, ec: ExecutionContext) =
    compressStream(src).runWith(StreamRefs.sourceRef())
      .map(s => NetworkSource(fmt, CompressedSourceRef[T](fmt, s)))
}

case class SourceRspList[T](fmt: DeltaFmt[T],
                            responses: Seq[StreamResponse[T]]) extends StreamResponse[T]

object StreamResponse {

  /**
    * The standard way of building a StreamResponse for a certain recipient.
    */
  def build[T](src: Source[T, NotUsed], recipient: ActorRef)
              (implicit ctx: ActorContext, fmt: DeltaFmt[T],
               mat: Materializer, ec: ExecutionContext): Future[StreamResponse[T]] =

    if (actorIsLocal(recipient))
      Future.successful(MemorySource(fmt, src))

    else NetworkSource.build(src)


  def buildList[T](sources: Seq[Source[T, NotUsed]], recipient: ActorRef)
                  (implicit ctx: ActorContext, fmt: DeltaFmt[T],
                   mat: Materializer, ec: ExecutionContext): Future[SourceRspList[T]] =
    Future.sequence(sources.map(build(_, recipient))).map(SourceRspList(fmt, _))

  /**
    * The standard way of creating and sending a StreamResponse.
    */
  def streamTo[T](src: Source[T, NotUsed], recipient: ActorRef)
                 (implicit ctx: ActorContext, fmt: DeltaFmt[T],
                  mat: Materializer, ec: ExecutionContext): Future[StreamResponse[T]] =
    build(src, recipient) pipeTo recipient


  implicit def apply[T](seq: Seq[StreamResponse[T]])
                       (implicit fmt: DeltaFmt[T]): SourceRspList[T] =
    SourceRspList(fmt, seq)

}
