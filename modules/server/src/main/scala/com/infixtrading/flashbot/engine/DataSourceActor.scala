package com.infixtrading.flashbot.engine

import java.util.concurrent.Executors

import akka.NotUsed
import akka.actor.{Actor, ActorLogging}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.infixtrading.flashbot.core.DataSource._
import com.infixtrading.flashbot.core.FlashbotConfig._
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.db._
import com.infixtrading.flashbot.engine.DataServer.{DataBundle, DataSelection}
import com.infixtrading.flashbot.models.core.DataPath
import com.infixtrading.flashbot.models.core.Slice.SliceId
import com.infixtrading.flashbot.util.stream._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

/**
  * An actor that runs a single instance of a data source. Supervised by DataServer.
  * It answers requests for data by sending out a SourceRsp that's ready to send data.
  * It is also indexes the data that it ingests, so that it can answer queries about what
  * data exists for what time frames.
  */
class DataSourceActor(jdbcUrl: String,
                      srcKey: String,
                      config: DataSourceConfig,
                      exchangeConfig: Option[ExchangeConfig],
                      ingestConfig: Option[IngestConfig]) extends Actor with ActorLogging {
  import DataSourceActor._

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))
  implicit val system = context.system
  implicit val mat = ActorMaterializer()

  // How often to save a snapshot to the db.
  val snapshotInterval = 4 hours

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

  // Build index of available data bundles at time of initialization.
  val initialIndex: Set[DataBundle] = ???

  // An index of data bundles for this incarnation of the actor. Initially empty, as ingest
  // hasn't started at time of initialization.
  var liveIndex = Map.empty[Long, DataBundle]

  // Initialize ingest when data is loaded.
  pathsFut andThen {
    case Success(_) => self ! Init(None)
    case Failure(err) => self ! Init(Some(err))
  }

  def ingestMsgOpt(queue: Seq[(String, Set[String])]): Option[Ingest] = {
    val filteredQueue = queue.filter(_._2.nonEmpty)
    if (filteredQueue.nonEmpty) Some(Ingest(filteredQueue)) else None
  }

  def matchers: Set[String] = ingestConfig.get.paths.toSet

  var itemBuffers = Map.empty[Long, Vector[BufferItem[_]]]

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

      if (ingestConfig.isDefined) {
        // Build initial queue
        val ingestQueue = for {
          topics <- topicsFut
          full = types.toSeq.map { case (dataType, _) => (dataType, topics) }
          _ = {
            log.debug("Full queue: {}", full)
          }
          queue = full.map {
            case (dt, ts) => (dt, ts.filter(topic =>
              matchers.exists(_.matches(DataPath(srcKey, topic, dt)))))
          }
        } yield queue

        // Send to self if there's anything to ingest.
        ingestQueue.andThen {
          case Success(queue) =>
            if (ingestMsgOpt(queue).isEmpty) {
              log.debug("Initial queue is empty for matchers: {}", matchers)
            }
            ingestMsgOpt(queue).foreach(self ! _)
          case Failure(err) => self ! Init(Some(err))
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
              case Left(topic) => dataSource.ingest(topic, fmt).map(src => Map(topic -> src))
              case Right(ts)   => dataSource.ingestGroup(ts, fmt)
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
                    val bundleId = createBundle(jdbcUrl, path)

                    // When the source is terminated, we "close" the bundle. Once a bundle is
                    // closed, we will never be able to add more data to it since a bundle id
                    // is logically tied to this materialization of the stream.
                    val (onTerminated, src: Source[(Long, T), NotUsed]) =
                      source.watchTermination()(Keep.right).preMaterialize()

                    onTerminated andThen {
                      case Success(value) =>
                        log.info("Ingest stream stopped {}", path)
                        context.stop(self)

                      case Failure(err) =>
                        log.error(err, s"Error in ingest stream {}", path)
                        self ! Err(err)
                    }

                    // Here is where we process the market data coming from ingest data sources.
                    src.zipWithIndex
                    // Buffer items.
                      .alsoTo(Sink.foreach {
                        case ((micros, item), seqId) =>
                          self ! BufferItem(path, item, bundleId, seqId, micros)
                      })
                      // Group items and batch insert into database.
                      .groupedWithin(1000, 1 second)
                      .mapAsync(10) { items: Seq[((Long, T), Long)] =>
                        ingestItemsAsync(bundleId, items)
                      }
                      // Clear ingested items from buffer.
                      .runWith(Sink.foreach { lastIngestedSeqId =>
                        self ! DataIngested(bundleId, lastIngestedSeqId)
                      })
                }

              case Failure(err) =>
                log.error(err, s"Ingest failure in DataSource $srcKey.")
                self ! Err(err)
            }
          }

        case None =>
          log.debug("Ingest scheduling complete for {}", srcKey)
      }

    case item @ BufferItem(path, _, bundleId, _, micros) =>
      val existing = itemBuffers.getOrElse(bundleId, Vector.empty)
      itemBuffers += (bundleId -> (existing :+ item))

      // Also update the live index.
      if (!liveIndex.isDefinedAt(bundleId)) {
        liveIndex += (bundleId -> DataBundle(path, bundleId, micros, None))
      }

    case DataIngested(bundleId, lastIngestedSeqId: Long) =>
      val existing = itemBuffers.getOrElse(bundleId, Vector.empty)
      // Buffer should never be empty at this point, but opting to not crash in that case.
      if (existing.isEmpty) {
        log.warning(s"Data buffer should not be empty for bundle id $bundleId")
      }
      itemBuffers += (bundleId -> existing.dropWhile(_.seqId <= lastIngestedSeqId))


    /**
     * ===========
     *   Queries
     * ===========
     */
    case Index =>
      // Respond with a set of DataBundles managed by this DataSourceActor.
      // This is the initial index (static bundles, which existed before this actor started)
      // and live index (live bundles, which are being ingested now).
      sender ! (initialIndex ++ liveIndex.values.toSet)

    case StreamMarketData(DataSelection(path, from, to)) =>


  }
}


object DataSourceActor {
  case class Init(err: Option[Throwable])
  case class Err(err: Throwable)
  case class Ingest(queue: Seq[(String, Set[String])])
  case class BufferItem[T](path: DataPath, data: T, bundleId: Long, seqId: Long, micros: Long)
  case class DataIngested(bundleId: Long, seqId: Long)
  case object Index

  case class StreamMarketData[T](selection: DataSelection) extends StreamRequest[T]
}
