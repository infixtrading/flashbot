package flashbot.util

import java.time.Instant

import akka.NotUsed
import akka.actor.{ActorContext, ActorPath, ActorRef, ActorRefFactory, ActorSystem, RootActorPath}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.util.Timeout
import akka.pattern.ask
import flashbot.models.{DataAddress, StreamRequest, TimeRange}
import flashbot.server.StreamResponse
import flashbot.util.time.FlashbotTimeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

package object stream {

  def initResource[R, E](build: E => R): Flow[E, (R, E), NotUsed] =
    Flow[E].scan[Option[(R, E)]](None) {
      case (None, ev) => Some(build(ev), ev)
      case (Some((resource, _)), ev) => Some(resource, ev)
    }.drop(1).map(_.get)

  def deDupeWithSeq[T](seqFn: T => Long): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Long, Option[T])](-1, None) {
    case ((seq, _), event) if seqFn(event) > seq => (seqFn(event), Some(event))
    case ((seq, _), _) => (seq, None)
  }.collect { case (_, Some(event)) => event }

  def deDupeVia[T](eqFn: (T, T) => Boolean): Flow[T, T, NotUsed] = Flow[T]
    .scan[(Option[T], Option[T])](None, None) { case ((_, b), ev) => (b, Some(ev)) }
    .collect( { case (last, Some(event)) if last.isEmpty || !eqFn(last.get, event)  => event })

  def deDupeBy[T, K](map: T => K): Flow[T, T, NotUsed] = deDupeVia[T]((a, b) => map(a) == map(b))

  def dropUnordered[U](ordering: Ordering[U]): Flow[U, U, NotUsed] =
    dropUnordered[U, U](x => x)(ordering)

  def dropUnordered[T, U](map: T => U)(implicit ordering: Ordering[U]): Flow[T, T, NotUsed] =
    Flow[T].scan[(Option[T], Option[U])]((None, None)) {
      case ((None, None), item) => (Some(item), Some(map(item)))
      case ((_, Some(max)), item) =>
        if (ordering.gt(map(item), max)) (Some(item), Some(map(item)))
        else (None, Some(max))
    } collect { case (Some(value), _) => value }

  def withIndex[T]: Flow[T, (Long, T), NotUsed] = Flow[T]
    .scan[(Long, Option[T])]((-1, None))((count, e) => (count._1 + 1, Some(e)))
    .drop(1)
    .map(e => (e._1, e._2.get))

  def buildMaterializer()(implicit system: ActorSystem): ActorMaterializer =
    ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy { err =>
      println(s"Exception in stream: $err")
      Supervision.Stop
    })(system)

  def iteratorToSource[T](it: Iterator[T])(implicit ec: ExecutionContext): Source[T, NotUsed] = {
    Source.unfoldAsync[Iterator[T], T](it) { memo =>
      // TODO: Can we use Future.success and Future.failure here?
      // I have a hunch it was causing weird errors and that's why I left it like this.
      // ALSO: Why not use unfold instead of unfoldAsync? I think that wasn't quite working either.
      // Gotta double check.
      Future {
        if (memo.hasNext) {
          Some(memo, memo.next)
        } else {
          None
        }
      }
    }
  }

  implicit class IterOps[T](it: Iterator[T]) {
    def toSource(implicit ec: ExecutionContext): Source[T, NotUsed] = iteratorToSource(it)
  }

  def actorPathsAreLocal(a: ActorPath, b: ActorPath): Boolean =
    RootActorPath(a.address) == RootActorPath(b.address)

  def actorIsLocal(other: ActorRef)(implicit context: ActorContext): Boolean =
    RootActorPath(context.self.path.address) == RootActorPath(other.path.address)

  def senderIsLocal(implicit context: ActorContext): Boolean = actorIsLocal(context.sender)

  implicit def toActorPath[T](dataAddress: DataAddress[T]): ActorPath =
    ActorPath.fromString(dataAddress.host.get)

  implicit class StreamRequester(ref: ActorRef) {
    def <<?[T](req: StreamRequest[T]): Future[StreamResponse[T]] =
      (ref ? req)(FlashbotTimeout.default) match {
        case fut: Future[StreamResponse[T]] => fut
      }
  }

  def tickTimeRange(range: TimeRange, timeStep: FiniteDuration): Source[Instant, NotUsed] = {
    val startAt = Instant.ofEpochMilli(range.start/1000)
    val endAt = Instant.ofEpochMilli(range.end/1000)
    val isRealTime = range.end == Long.MaxValue
    if (isRealTime)
      Source.tick(0 seconds, timeStep, "").zipWithIndex.map(_._2)
        .map(i => startAt.plusMillis(i * timeStep.toMillis)).mapMaterializedValue(_ => NotUsed)
    else Source.unfold(startAt)(item => {
      val next = item.plusMillis(timeStep.toMillis)
      Some((next, item))
    }).takeWhile(_.isBefore(endAt))
  }

  implicit class StreamOps[T](stream: Stream[T]) {

    def scanPrev: Stream[(Option[T], T)] = {
      val init: (Option[T], Option[T]) = (None, None)
      stream.scanLeft(init) {
        case ((None, None), item) => (None, Some(item))
        case ((None, Some(a)), item) => (Some(a), Some(item))
        case ((Some(_), Some(a)), item) => (Some(a), Some(item))
      } drop 1 map (x => (x._1, x._2.get))
    }

    def dropUnordered(implicit ordering: Ordering[T]): Stream[T] =
      _dropByOrdering(stream, allowDupes = true, allowUnordered = false)

    def dropDuplicates(implicit ordering: Ordering[T]): Stream[T] =
      _dropByOrdering(stream, allowDupes = false, allowUnordered = true)
  }

  private def _dropByOrdering[T](stream: Stream[T], allowDupes: Boolean, allowUnordered: Boolean)
                                (implicit ordering: Ordering[T]): Stream[T] =
    stream.scanPrev filter {
      case (Some(prev), item) =>
        val order = ordering.compare(prev, item)
        val isDupe = order == 0
        val isUnordered = order > 0
        (allowDupes || !isDupe) && (allowUnordered || !isUnordered)
      case _ => true
    } map (_._2)

}
