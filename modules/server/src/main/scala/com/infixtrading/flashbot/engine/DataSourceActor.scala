package com.infixtrading.flashbot.engine

import java.util.concurrent.Executors

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.pattern.pipe
import akka.stream.alpakka.slick.javadsl.SlickSession
import com.infixtrading.flashbot.core.DataSource._
import com.infixtrading.flashbot.core.FlashbotConfig._
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.db._
import com.infixtrading.flashbot.engine.DataServer.DataSelection
import com.infixtrading.flashbot.models.core.DataPath
import com.infixtrading.flashbot.models.core.Slice.SliceId
import com.infixtrading.flashbot.util.stream._
import com.typesafe.config.Config
import io.circe.Printer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success}

/**
  * An actor that runs a single instance of a data source. Supervised by DataServer.
  * It answers requests for data by sending out a SourceRsp that's ready to send data.
  * It is also indexes the data that it ingests, so that it can answer queries about what
  * data exists for what time frames.
  */
class DataSourceActor(session: SlickSession,
                      srcKey: String,
                      config: DataSourceConfig,
                      exchangeConfig: Option[ExchangeConfig],
                      ingestConfig: IngestConfig) extends Actor with ActorLogging {
  import DataSourceActor._
  import session.profile.api._

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))
  implicit val system = context.system
  implicit val mat = ActorMaterializer()
  implicit val slickSession = session

  val random = new Random()

  // Create the instance of the DataSource.
  val dataSource = getClass.getClassLoader
    .loadClass(config.`class`).getConstructor()
    .newInstance().asInstanceOf[DataSource]

  // Load all datatypes.
  val types: Map[String, DeltaFmtJson[_]] =
    config.datatypes.getOrElse(Seq.empty).foldLeft(dataSource.types)((memo, dt) =>
      memo + (dt -> DataType.parse(dt).get.fmtJson))

  // Load topics and paths
  val topicsFut = dataSource.discoverTopics(exchangeConfig)
  val pathsFut: Future[Set[DataPath]] = topicsFut.map(topics =>
    topics.flatMap(topic => types.keySet.map(dt => DataPath(srcKey, topic, dt))))

  // Initialize ingest when data is loaded.
  pathsFut andThen {
    case Success(_) => self ! Init(None)
    case Failure(err) => self ! Init(Some(err))
  }

  def ingestMsgOpt(queue: Seq[(String, Set[String])]): Option[Ingest] = {
    val filteredQueue = queue.filter(_._2.nonEmpty)
    if (filteredQueue.nonEmpty) Some(Ingest(filteredQueue)) else None
  }

  def matchers: Set[String] = ingestConfig.enabled.toSet

  var itemBuffers = Map.empty[Long, Vector[MarketData[_]]]

  var subscriptions = Map.empty[DataPath, Set[ActorRef]]

  var bundleIndex = Map.empty[DataPath, Seq[Long]]

  override def postStop() = {
    // Close all subscriptions on stop.
    for (ref <- subscriptions.values.flatten) {
      ref ! PoisonPill
    }
  }

  override def receive = {

    /**
     * ==================
     *   Error handling
     * ==================
     */
    case Init(Some(err)) => throw err
    case Err(err)        => throw err


    /**
     * ==================
     *   Initialization
     * ==================
     */
    case Init(None) =>
      log.debug("{} DataSource initialized", srcKey)

      if (ingestConfig.enabled.nonEmpty) {
        // Build initial queue
        val ingestQueue = for {
          topics <- topicsFut
          full = types.toSeq.map { case (dataType, _) => (dataType, topics) }
          _ = {
            log.debug("Full queue: {}", full)
          }
          queue = full.map {
            case (dt, ts) =>
              (dt, ts.filter(topic =>
                matchers.exists(_.matches(DataPath(srcKey, topic, dt)))))
          }
        } yield queue

        ingestQueue.andThen {
          case Success(queue) =>
            // Send Ingest message to self if there's anything to ingest.
            if (ingestMsgOpt(queue).isEmpty) {
              log.debug("Initial queue is empty for matchers: {}", matchers)
            } else {
              log.debug(s"Beginning ingest for $srcKey")
            }
            ingestMsgOpt(queue).foreach(self ! _)

            // Also start the backfill service.
            context.actorOf(Props(new BackfillService(session)))

          case Failure(err) =>
            self ! Init(Some(err))
        }
      }


    /**
     * ==========
     *   Ingest
     * ==========
     */
    case Ingest(queue) =>
      log.debug("Ingest action for {}: {}", srcKey, matchers.map(_.toString).mkString(","))

      // Take the first topic set and schedule
      queue.headOption match {
        case Some((dataType, topicSet)) =>
          log.debug("Scheduling ingest for {}/{} with topics: {}",
            srcKey, dataType, topicSet.mkString(", "))

          runScheduledIngest(types(dataType))

          // We got a data type and some topics to ingest. Get the data streams from the data source
          // instance, drop all data that doesn't match the ingest config, and return the streams.
          def getStreams[T](topics: Either[String, Set[String]],
                            delay: Duration, matchers: Set[DataPath])
                           (implicit fmt: DeltaFmtJson[T]) = for {
            _ <- Future { Thread.sleep(delay.toMillis) }

            streams <- topics match {
              case Left(topic) => dataSource.ingest[T](topic, DataType(dataType))
                .map(src => Map(topic -> src))
              case Right(ts)   => dataSource.ingestGroup[T](ts, DataType(dataType))
            }
          } yield streams.filterKeys(topic =>
            matchers.exists(_.matches(DataPath(srcKey, topic, dataType))))

          def runScheduledIngest[T](implicit fmt: DeltaFmtJson[T]) = {
            var scheduledTopics = Set.empty[String]
            val fut = dataSource.scheduleIngest(topicSet, dataType) match {
              case IngestOne(topic, delay) =>
                scheduledTopics = Set(topic)
                getStreams(Left(topic), delay, matchers.map(DataPath.parse))
              case IngestGroup(topics, delay) =>
                scheduledTopics = topics
                getStreams(Right(topics), delay, matchers.map(DataPath.parse))
            }
            val remainingTopics = topicSet -- scheduledTopics
            val newQueue =
              if (remainingTopics.nonEmpty) (dataType, remainingTopics) +: queue.tail
              else queue.tail

            fut onComplete {
              case Success(sources) =>
                // Start the next ingest cycle
                ingestMsgOpt(newQueue).foreach(self ! _)

                // Run the source for each topic, save to disk, and buffer.
                sources.foreach {
                  case (topic, source) =>
                    val path = DataPath(srcKey, topic, dataType)
                    createBundle(path) onComplete {
                      case Failure(err) =>
                        self ! Err(err)
                      case Success(bundleId) =>
                        log.info(s"Ingesting $path")

                        // Save bundle id for this path.
                        bundleIndex += (path ->
                          (bundleIndex.getOrElse(path, Seq.empty[Long]) :+ bundleId))

                        subscriptions += (path -> Set.empty[ActorRef])

                        case class ScanState(lastSnapshotAt: Long, seqId: Long,
                                             micros: Long, item: T, deltas: Seq[fmt.D],
                                             snapshot: Option[T])

                        // Here is where we process the market data coming from ingest data sources.
                        source.zipWithIndex
                          // Buffer items.
                          .alsoTo(Sink.foreach {
                            case ((micros, item), seqId) =>
                              self ! BaseMarketData(item, path, micros, bundleId, seqId)

                          })
                          // Scan to determine the deltas and snapshots to write on every iteration.
                          .scan[Option[ScanState]](None) {
                            case (None, ((micros, item), seqId)) =>
                              Some(ScanState(micros, seqId, micros, item, Seq.empty, Some(item)))
                            case (Some(prev), ((micros, item), seqId)) =>
                              val shouldSnapshot =
                                (micros - prev.lastSnapshotAt) >= SnapshotInterval.toMicros
                              Some(ScanState(
                                if (shouldSnapshot) micros else prev.lastSnapshotAt,
                                seqId, micros, item, fmt.diff(prev.item, item),
                                if (shouldSnapshot) Some(item) else None)
                              )
                          }
                          .collect { case Some(state) => state }
                          // Group items and batch insert into database.
                          .groupedWithin(1000, 1000 millis)
                          .mapAsync(10) { states: Seq[ScanState] =>
                            for {
                              // Save the deltas
                              a <- session.db.run(Deltas ++= states.flatMap(state =>
                                state.deltas.map(delta =>
                                  DeltaRow(bundleId, state.seqId, state.micros,
                                    fmt.deltaEn(delta).pretty(Printer.noSpaces)))
                              ))
                              // Save the snapshots
                              b <- session.db.run(Snapshots ++=
                                states.filter(_.snapshot.isDefined).map(state =>
                                  SnapshotRow(bundleId, state.seqId, state.micros,
                                    fmt.modelEn(state.item).pretty(Printer.noSpaces))
                                ))
                            } yield states.last.seqId
                          }
                          // Clear ingested items from buffer.
                          .runWith(Sink.foreach { lastIngestedSeqId =>
                            self ! DataIngested(bundleId, lastIngestedSeqId)
                          })
                          // When the source is terminated, we "close" the bundle. Once a bundle is
                          // closed, we will never be able to add more data to it since a bundle id
                          // is logically tied to this materialization of the stream.
                          .andThen {
                            case Success(Done) =>
                              log.info("Ingest stream stopped {}", path)
                              context.stop(self)

                            case Failure(err) =>
                              log.error(err, s"Error in ingest stream {}", path)
                              self ! Err(err)
                          }
                    }

                }

              case Failure(err) =>
                log.error(err, s"Ingest failure in DataSource $srcKey.")
                self ! Err(err)
            }
          }

        case None =>
          log.debug("Ingest scheduling complete for {}", srcKey)
      }

    /**
      * Upon incoming market data, we add it to the corresponding buffer and also broadcast the
      * market data item to the subscriptions.
      */
    case item: MarketData[_] =>
      // Add to buffer
      val existing = itemBuffers.getOrElse(item.bundle, Vector.empty)
      itemBuffers += (item.bundle -> (existing :+ item))

      // Broadcast
      subscriptions.get(item.path).foreach(refs => refs.foreach(_ ! item))

    case DataIngested(bundleId, lastIngestedSeqId: Long) =>
      val existing = itemBuffers.getOrElse(bundleId, Vector.empty)
      // Buffer should never be empty at this point, but opting to not crash in that case.
      if (existing.isEmpty) {
        log.warning(s"Data buffer should not be empty for bundle id $bundleId")
      }
      itemBuffers += (bundleId -> existing.dropWhile(_.seqid <= lastIngestedSeqId))


    /**
     * ===========
     *   Queries
     * ===========
     */
    case StreamLiveData(path) =>
      def buildOptSrc[T](fmt: DeltaFmtJson[T]): Option[Source[MarketData[T], NotUsed]] =
        if (subscriptions.isDefinedAt(path)) {
          val (ref, src) =
            Source.actorRef[MarketData[T]](Int.MaxValue, OverflowStrategy.fail).preMaterialize()
          subscriptions += (path -> (subscriptions(path) + ref))
          val initialItems: Vector[MarketData[T]] = lookupBundleId(path)
            .flatMap(itemBuffers.get(_).map(_.asInstanceOf[Vector[MarketData[T]]]) )
            .getOrElse(Vector.empty[MarketData[T]])
          Some(Source(initialItems).concat(src))
        } else None
      sender ! buildOptSrc(DeltaFmt.formats(path.datatype))
  }

  def lookupBundleId(path: DataPath): Option[Long] = bundleIndex.get(path).map(_.last)
}


object DataSourceActor {
  case class Init(err: Option[Throwable])
  case class Err(err: Throwable)
  case class Ingest(queue: Seq[(String, Set[String])])
  case class DataIngested(bundleId: Long, seqId: Long)
  case object Index

  case class DataBundle(path: DataPath, bundleId: Long, begin: Long, end: Option[Long])

  case class StreamLiveData[T](path: DataPath) extends StreamRequest[T]

  // How often to save a snapshot to the db.
  val SnapshotInterval = 4 hours
}
