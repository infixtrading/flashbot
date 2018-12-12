package com.infixtrading.flashbot.core

import akka.NotUsed
import akka.actor.{ActorContext, ActorPath, ActorRef, ActorSystem}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.typesafe.config.Config
import de.sciss.fingertree.{FingerTree, RangedSeq}
import io.circe.Json
import io.circe.generic.auto._
import com.infixtrading.flashbot.core.DataSource._
import com.infixtrading.flashbot.core.InfixConfig.ExchangeConfig
import com.infixtrading.flashbot.core.Slice.SliceId
import com.infixtrading.flashbot.util.time.parseDuration
import com.infixtrading.flashbot.util.stream._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration._

abstract class DataSource(dataTypes: Set[String]) {

  def discoverTopics(exchangeConfig: Option[ExchangeConfig])
                    (implicit ctx: ActorContext, mat: ActorMaterializer): Future[Set[String]] =
    Future.successful(exchangeConfig.map(_.pairs.map(_.toString)).getOrElse(Seq.empty).toSet)

  def types: Map[String, DeltaFmtJson[_]] = Map.empty

  def scheduleIngest(topics: Set[String], dataType: String): IngestSchedule =
    IngestOne(topics.head, 0 seconds)

  def ingestGroup[T](topics: Set[String], fmt: DeltaFmtJson[T])
                    (implicit ctx: ActorContext,
                     mat: ActorMaterializer): Future[Map[String, Source[(Long, T), NotUsed]]] =
    Future.failed(new NotImplementedError("ingestGroup is not implemented by this data source."))

  def ingest[T](topic: String, fmt: DeltaFmtJson[T])
               (implicit ctx: ActorContext,
                mat: ActorMaterializer): Future[Source[(Long, T), NotUsed]] =
    Future.failed(new NotImplementedError("ingest is not implemented by this data source."))
}

object DataSource {

  sealed trait IngestSchedule {
    def delay: Duration
  }
  final case class IngestGroup(topics: Set[String], delay: Duration) extends IngestSchedule
  final case class IngestOne(topic: String, delay: Duration) extends IngestSchedule

  final case class DataTypeConfig(retention: Option[String])


//  object DataSourceConfig {
//    def build(config: Config): DataSourceConfig = {
//      var topics = Seq.empty[String]
//      try {
//        topics = config.getStringList("topics").asScala
//      }
//
//      var datatypes = Seq.empty[String]
//      try {
//        datatypes = config.getStringList("datatypes").asScala
//      }
//
//      DataSourceConfig(
//        `class` = config.getString("class"),
//        topics = topics.toSet,
//        dataTypes = datatypes.toSet
//      )
//    }
//  }


  trait Mergable[T] extends Any {
    def merge(other: T): T
  }


//  case class SliceMatch(bundle: Option[Long] = None, slice: Option[Long] = None) {
//    def matches(sliceId: SliceId): Boolean =
//      bundle.forall(_ == sliceId.bundle) && slice.forall(_ == sliceId.slice)
//  }

  implicit class SliceIndex(val rangedSlices: RangedSeq[Slice, Long])
      extends AnyVal with Mergable[SliceIndex] {

    override def merge(other: SliceIndex) = other.rangedSlices

    def bundleId: Long = rangedSlices.head.id.bundle
    def filterOverlaps(from: Long, to: Long): SliceIndex = rangedSlices.filterOverlaps((from, to)).toSeq
    def slices: Seq[Slice] = rangedSlices.filterOverlaps((0, Long.MaxValue)).toSeq
    def filterId(fn: SliceId => Boolean): SliceIndex = slices.filter(s => fn(s.id))
    def isEmpty: Boolean = slices.isEmpty
    def startMicros: Long = rangedSlices.head.fromMicros
    def endMicros: Long = rangedSlices.last.toMicros

    def +(slice: Slice): SliceIndex = rangedSlices + slice

    def bundles: Map[Long, SliceIndex] =
      slices.groupBy(_.id.bundleValue.get).mapValues(SliceIndex.build)
  }
  object SliceIndex {
    def empty: SliceIndex = Seq.empty

    implicit def build(seq: Seq[Slice]): SliceIndex = new SliceIndex(
      RangedSeq[Slice, Long](seq: _*)(
        slice => (slice.fromMicros, slice.toMicros),
        Ordering[Long]
      )
    )
  }

  class DataTypeIndex(val bundles: Map[String, SliceIndex]) extends AnyVal
      with Mergable[DataTypeIndex] {
    def get(dataType: String) = bundles.get(dataType)
    def apply(dataType: String) = get(dataType).get
    def merge(other: DataTypeIndex) = mergeMap(bundles, other.bundles)
    def isEmpty = bundles.isEmpty

    def slices = bundles.flatMap {
      case (dataType, index) =>
        index.slices.map(_.mapAddress(_.withType(dataType)))
    }.toSeq
  }

  object DataTypeIndex {
    implicit def build(bundles: Map[String, SliceIndex]): DataTypeIndex =
      new DataTypeIndex(bundles.filterNot(_._2.isEmpty))

    implicit def build(slices: Seq[Slice]): DataTypeIndex =
      slices.groupBy(_.typeValue.get).mapValues(SliceIndex.build)
  }

  class TopicIndex(val topics: Map[String, DataTypeIndex]) extends AnyVal
      with Mergable[TopicIndex] {

    def get(topic: String) = topics.get(topic)
    def apply(topic: String) = get(topic).get
    def merge(other: TopicIndex) = mergeMap(topics, other.topics)
    def filter(fn: ((String, DataTypeIndex)) => Boolean): TopicIndex = topics.filter(fn)
    def isEmpty = topics.isEmpty

    def slices = topics.flatMap {
      case (topic, index) =>
        index.slices.map(_.mapAddress(_.withTopic(topic)))
    }.toSeq
  }

  object TopicIndex {
    implicit def build(topics: Map[String, DataTypeIndex]): TopicIndex =
      new TopicIndex(topics.filterNot(_._2.isEmpty))

    implicit def build(slices: Seq[Slice]): TopicIndex =
      slices.groupBy(_.topicValue.get).mapValues(DataTypeIndex.build)
  }

  class DataSourceIndex(val sources: Map[String, TopicIndex]) extends AnyVal
      with Mergable[DataSourceIndex] {
    def get(dataSource: String) = sources.get(dataSource)
    def apply(dataSource: String) = get(dataSource).get
    def apply(path: DataPath) = (for {
      topics <- get(path.source)
      types <- topics.get(path.topic)
      bundles <- types.get(path.dataType)
    } yield bundles).getOrElse(SliceIndex.empty)

    def merge(other: DataSourceIndex) = mergeMap(sources, other.sources)
    def isEmpty = sources.isEmpty
    def slices = sources.flatMap {
      case (src, topics) => topics.slices.map(_.mapAddress(_.withSource(src)))
    }.toSeq

    def filter(pattern: DataPath): DataSourceIndex = slices.filter(slice =>
      slice.address.exists(_.path.matches(pattern)))

    def paths: Seq[DataPath] = slices.map(_.address.get.path)
  }

  object DataSourceIndex {
    def empty: DataSourceIndex = new DataSourceIndex(Map.empty)
    implicit def build(sources: Map[String, TopicIndex]): DataSourceIndex =
      new DataSourceIndex(sources.filterNot(_._2.isEmpty))

    implicit def build(slices: Seq[Slice]): DataSourceIndex =
      slices.groupBy(_.sourceValue.get).mapValues(TopicIndex.build)
  }

  class DataClusterIndex(val members: Map[ActorPath, DataSourceIndex]) extends AnyVal
      with Mergable[DataClusterIndex] {

    def merge(other: DataClusterIndex) = mergeMap(members, other.members)

    def slices = members.flatMap {
      case (host, index) => index.slices.map(_.mapAddress(_.withHost(host.toString)))
    }.toSeq

    def filter(pattern: DataPath): DataClusterIndex = members.mapValues(_.filter(pattern))

    /**
      * Use the index to plan a sequence of scans that, when concatenated together, fulfill
      * query in an optimal way. Scans from the `self` actor are always preferred due to locality.
      * This is essentially a transformation from a logical StreamSelection to a sequence of
      * ClusterStreamSelection.
      */
    def planQuery(self: ActorRef, selection: StreamSelection): Seq[ClusterStreamSelection] = {
      assert(selection.path.value.isDefined, "Pattern queries are not yet supported.")
      val filteredIndex = filter(selection.path)
      val sliceIdx: SliceIndex = filteredIndex.slices
      val idx = sliceIdx.filterOverlaps(selection.from, selection.to)

      def helper(memo: Seq[ClusterStreamSelection], cursor: Long): Seq[ClusterStreamSelection] = {
        val cursorInBounds = cursor >= selection.from && cursor < selection.to
        if (cursorInBounds) {
          // If the cursor is in bounds, then we still may have some work to do.
          // First, try to see what slices intersect with it.
          val intersections = idx.rangedSlices.intersect(cursor).toSeq
          val localIntersections = intersections.filter(slice =>
            actorPathsAreLocal(self.path, ActorPath.fromString(slice.address.get.host.get)))

          // Are there any intersections? If so, pick the best one. Slices that have the same
          // host as `self` automatically win over all others. Otherwise, the criteria for the
          // best slice is how far it extends beyond the current cursor.
          if (intersections.nonEmpty) {
            val activeIntersections =
              if (localIntersections.nonEmpty) localIntersections
              else intersections
            val best = activeIntersections.maxBy(_.toMicros)
            val polling = selection.polling
            val end = if (polling) Long.MaxValue else math.min(best.toMicros, selection.to)
            val newSelection = ClusterStreamSelection(best.address.get, cursor, end, polling)
            helper(memo :+ newSelection, end)

          } else {
            // If there are no intersections, then this is a gap in the data. Let's seek the
            // cursor to the beginning of the next slice.
            helper(memo, idx.slices.map(_.fromMicros).filter(_ > cursor).min)
          }
        } else {
          // If cursor not in bounds we're done, return.
          memo
        }
      }

      helper(Seq.empty, selection.from)
    }
  }

  object DataClusterIndex {
    def empty: DataClusterIndex = Map.empty[ActorPath, DataSourceIndex]
    def apply(self: ActorPath, sourceIndex: DataSourceIndex): DataClusterIndex =
      Map(self -> sourceIndex)

    implicit def build(members: Map[ActorPath, DataSourceIndex]): DataClusterIndex =
      new DataClusterIndex(members.filterNot(_._2.isEmpty))

    implicit def build(slices: Seq[Slice]): DataClusterIndex =
      slices.groupBy(_.address.get.host.map(ActorPath.fromString).get)
        .mapValues(DataSourceIndex.build)
  }

  def mergeMap[K, T <: Mergable[T]](a: Map[K, T], b: Map[K, T]): Map[K, T] = {
    val commonKeys = a.keySet.intersect(b.keySet)
    val commonMerged = commonKeys.map(key => key -> a(key).merge(b(key)))
    (a -- commonKeys) ++ (b -- commonKeys) ++ commonMerged
  }

  case class Bundle(id: Long, fromMicros: Long, toMicros: Long)

  /**
    * A logical description of some data that can be streamed. Intentionally leaves out lower
    * level details such as data address hosts, slices/bundles, and data locality constraints.
    */
  case class StreamSelection(path: DataPath, from: Long, to: Long, polling: Boolean)
  object StreamSelection {
    def apply(path: DataPath, from: Long, polling: Boolean): StreamSelection =
      StreamSelection(path, from, Long.MaxValue, polling)
    def apply(path: DataPath, from: Long, to: Long): StreamSelection =
      StreamSelection(path, from, to, polling = false)
    def apply(path: DataPath, from: Long): StreamSelection =
      StreamSelection(path, from, Long.MaxValue, polling = false)

    implicit def fromClusterSelection(cs: ClusterStreamSelection): StreamSelection = cs.toLocal
  }

  /**
    * Like StreamSelection but uses a DataAddress instead of a DataPath so that it can reference
    * remote nodes.
    */
  case class ClusterStreamSelection(address: DataAddress, from: Long, to: Long, polling: Boolean) {
    def toLocal: StreamSelection = StreamSelection(address.path, from, to, polling)
  }

//  def reindex(sources: Iterable[Iterable[MarketData[_]]]) =
//    sources.zipWithIndex.map { case (mds, i) => mds.map(_.withBundle(i))}
//
  def reindex(sources: List[Source[MarketData[_], NotUsed]]) =
    sources.zipWithIndex.map { case (mds, i) => mds.map(_.withBundle(i))}

//  def reindex(sources: Source[Source[MarketData[_], NotUsed], NotUsed]) =
//    sources.zipWithIndex.map { case (mds, i) => mds.map(_.withBundle(i.toInt))}
}

