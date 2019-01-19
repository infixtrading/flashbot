package com.infixtrading.flashbot.engine

import akka.NotUsed
import akka.stream.SourceRef
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.{DeltaFmt, FoldFmt}

/**
  * CompressedSourceRef is a wrapper around an Akka SourceRef that holds the compressed version
  * of a [[T]] stream for which there is a known [[DeltaFmt]] type class instance.
  *
  * @param fmt the [[DeltaFmt]] which is the brains behind how to compress a [[T]].
  * @param compressedStreamRef the source ref which will be sent over the network.
  * @tparam T the raw uncompressed type
  */
case class CompressedSourceRef[T](fmt: DeltaFmt[T],
                                  compressedStreamRef: SourceRef[Either[T, _]])

object CompressedSourceRef {

  sealed trait CompressionState
  case object Zero extends CompressionState
  case class Fold[T](data: T, parts: Seq[T]) extends CompressionState
  case class Delta[T, D](data: T, delta: D) extends CompressionState

  def decompressStream[T](fmt: DeltaFmt[T],
                          stream: Source[Either[T, _], NotUsed]): Source[T, NotUsed] =
    stream.scan[CompressionState](Zero) {
      // Base case. First element must always be Left. At this point, we don't know if there
      // will be more Left elements, so we go into Fold state. Note that the `parts` field of
      // the Fold state is unused during decompression.
      case (Zero, Left(part)) => Fold(part, Seq.empty)

      // We got another Left, fold it in, and continue in Folding state.
      case (Fold(prevData: T, empty), Left(part)) => Fold(fmt.fold(prevData, part), empty)

      // We got a Right while in the Folding state, time to move on to the Delta state.
      case (Fold(prevData: T, empty), Right(delta)) =>
        Delta(fmt.update(prevData, delta.asInstanceOf[fmt.D]), Seq.empty)

      // Once we enter Delta state, we do not expect any more Lefts.
      case (Delta(prevData: T, empty), Right(delta)) =>
        Delta(fmt.update(prevData, delta.asInstanceOf[fmt.D]), empty)
    }.collect {
      case Fold(data: T, _) => data
      case Delta(data: T, _) => data
    }

  def compressStream[T](stream: Source[T, NotUsed])
                       (implicit fmt: DeltaFmt[T]): Source[Either[T, _], NotUsed] =
    stream.scan[CompressionState](Zero) {
      case (Zero, data) => Fold(data, FoldFmt.unfoldData(data))
      case (Fold(prevData: T, _), data) => Delta(data, fmt.diff(prevData, data))
      case (Delta(prevData: T, _), data) => Delta(data, fmt.diff(prevData, data))
    }.flatMapConcat {
      case Zero => Source.empty
      case Fold(_, parts: Seq[T]) => Source(parts.toList.map(Left(_)))
      case Delta(_, delta: fmt.D) => Source.single(Right(delta))
    }
}
