package flashbot.server

import java.util.concurrent.{ConcurrentHashMap, Executors}

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Props}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.pattern.pipe
import akka.stream.alpakka.slick.javadsl.SlickSession
import flashbot.core.DataSource._
import flashbot.core.MarketData.BaseMarketData
import flashbot.core._
import flashbot.db._
import flashbot.util.stream._
import com.typesafe.config.Config
import flashbot.core.FlashbotConfig.{DataSourceConfig, ExchangeConfig, IngestConfig}
import flashbot.core.{DataType, DeltaFmt, DeltaFmtJson, MarketData}
import flashbot.models.{DataPath, StreamLiveData}
import io.circe.Printer

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import scala.concurrent.blocking
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

  implicit val system = context.system
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat = buildMaterializer()
  implicit val slickSession = session

  val random = new Random()

  // Create the instance of the DataSource.
  val dataSource = getClass.getClassLoader
    .loadClass(config.`class`).getConstructor()
    .newInstance().asInstanceOf[DataSource]

  // Load all datatypes.
  val types: Seq[DataType[_]] =
    config.datatypes.getOrElse(Seq.empty)
      .foldLeft(dataSource.types)((memo, str) => memo :+ DataType.parse(str).get)

  // Load topics and paths
  val topicsFut = dataSource.discoverTopics(exchangeConfig)
  val pathsFut: Future[Set[DataPath[_]]] = topicsFut.map(topics =>
    topics.flatMap(topic => types.map(dt => DataPath(srcKey, topic, DataType.parse(dt).get))))

  // Initialize ingest when data is loaded.
  pathsFut andThen {
    case Success(_) => self ! Init(None)
    case Failure(err) => self ! Init(Some(err))
  }

  def ingestMsgOpt(queue: Seq[(DataType[_], Set[String])]): Option[Ingest] = {
    val filteredQueue = queue.filter(_._2.nonEmpty)
    if (filteredQueue.nonEmpty) Some(Ingest(filteredQueue)) else None
  }

  var itemBuffers = Map.empty[Long, Vector[MarketData[_]]]

  var subscriptions = new ConcurrentHashMap[String, Set[ActorRef]]

  var bundleIndex = new ConcurrentHashMap[String, Seq[Long]]

  override def postStop() = {
    // Close all subscriptions on stop.
    for (ref <- subscriptions.values().asScala.toSet.flatten) {
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
          queue = types.map((_, topics)).map {
            case (dt, ts) =>
              (dt, ts.filter(topic =>
                ingestConfig.ingestMatchers.exists(_.matches(DataPath(srcKey, topic, DataType.parse(dt).get)))))
          }
        } yield queue

        ingestQueue.andThen {
          case Success(queue) =>
            // Send Ingest message to self if there's anything to ingest.
            if (ingestMsgOpt(queue).isEmpty) {
              log.debug("Initial queue is empty for matchers: {}", ingestConfig.ingestMatchers)
            } else {
              log.debug(s"Beginning ingest for $srcKey")
            }
            ingestMsgOpt(queue).foreach(self ! _)

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
      log.debug("Ingest action for {}: {}", srcKey,
        ingestConfig.ingestMatchers.map(_.toString).mkString(","))

      // Take the first topic set and schedule
      queue.headOption match {
        case Some((dataType, topicSet)) =>
          log.debug("Scheduling ingest for {}/{} with topics: {}",
            srcKey, dataType, topicSet.mkString(", "))

          runScheduledIngest(dataType)

          // We got a data type and some topics to ingest. Get the data streams from the data source
          // instance, drop all data that doesn't match the ingest config, and return the streams.
          def getStreams[T](topics: Either[String, Set[String]],
                            delay: Duration, matchers: Set[DataPath[Any]]) = for {
            _ <- Future(blocking {
              Thread.sleep(delay.toMillis)
            })

            streams <- topics match {
              case Left(topic) =>
                log.debug("Requesting ingest stream for {}/{}", topic, dataType)
                dataSource.ingest[T](topic, dataType.asInstanceOf[DataType[T]])
                  .andThen { case x =>
                    log.debug("Got ingest stream for {}/{}: {}", topic, dataType, x)
                  }
                  .map(src => Map(topic -> src))
              case Right(ts) =>
                dataSource.ingestGroup[T](ts, dataType.asInstanceOf[DataType[T]])
            }
            _ = log.debug("Ingest streams: {}", streams)
          } yield streams.filterKeys(topic =>
            matchers.exists(_.matches(DataPath(srcKey, topic, dataType))))

          def runScheduledIngest[T](dt: DataType[T]) = {
            var scheduledTopics = Set.empty[String]
            val fmt: DeltaFmtJson[T] = dt.fmtJson
            val fut = dataSource.scheduleIngest(topicSet, dataType) match {
              case IngestOne(topic, delay) =>
                scheduledTopics = Set(topic)
                getStreams[T](Left(topic), delay, ingestConfig.ingestMatchers)
              case IngestGroup(topics, delay) =>
                scheduledTopics = topics
                getStreams[T](Right(topics), delay, ingestConfig.ingestMatchers)
            }
            val remainingTopics = topicSet -- scheduledTopics
            val newQueue =
              if (remainingTopics.nonEmpty) (dataType, remainingTopics) +: queue.tail
              else queue.tail

            fut andThen {
              case x =>
                log.debug("Received streams: {}", x)
            } onComplete {
              case Success(sources) =>
                // Start the next ingest cycle
                ingestMsgOpt(newQueue).foreach(self ! _)

                // Run the source for each topic, save to disk, and buffer.
                sources.foreach {
                  case (topic, source) =>
                    val path = DataPath(srcKey, topic, dataType)
                    createBundles(path) onComplete {
                      case Failure(err) =>
                        self ! Err(err)
                      case Success((backfillBundleId, ingestBundleId)) =>
                        log.info(s"Ingesting $path")

                        // Start a backfill service for ingesting historical data and deleting data
                        // that's older than the retention period.
                        if (ingestConfig.backfillMatchers.exists(_.matches(path))) {
                          log.debug(s"Launching BackfillService for {}", path)
                          context.actorOf(Props(new BackfillService(backfillBundleId, path, dataSource)))
                        } else {
                          log.debug("Skipping backfill for {}", path)
                        }

                        // Start a retention service for every paths.
                        log.debug(s"Launching RetentionService for {}", path)
                        context.actorOf(Props(new RetentionService(path,
                          ingestConfig.retentionFor(path), dataSource.backfillTickRate)))


                        bundleIndex.computeIfAbsent(path.toString, _ => Seq.empty[Long])
                        bundleIndex.computeIfPresent(path.toString, (_, s) => s :+ ingestBundleId)

                        subscriptions.computeIfAbsent(path.toString, _ => Set.empty[ActorRef])
                        subscriptions.computeIfPresent(path.toString, (_, s) => s)

                        case class ScanState(lastSnapshotAt: Long, seqId: Long,
                                             micros: Long, item: T, delta: Option[fmt.D],
                                             snapshot: Option[T])

                        // Here is where we process the market data coming from ingest data sources.
                        source.zipWithIndex
                          // Buffer items.
                          .alsoTo(Sink.foreach {
                            case ((micros, item), seqId) =>
                              self ! BaseMarketData(item, path, micros, ingestBundleId, seqId)
                          })
                          // Scan to determine the deltas and snapshots to write on every iteration.
                          .scan[Option[ScanState]](None) {
                            case (None, ((micros, item), seqId)) =>
                              Some(ScanState(micros, seqId, micros, item, None, Some(item)))
                            case (Some(prev), ((micros, item), seqId)) =>
                              val shouldSnapshot =
                                (micros - prev.lastSnapshotAt) >= SnapshotInterval.toMicros
                              Some(ScanState(
                                if (shouldSnapshot) micros else prev.lastSnapshotAt,
                                seqId, micros, item, Some(fmt.diff(prev.item, item)),
                                if (shouldSnapshot) Some(item) else None)
                              )
                          }
                          .collect { case Some(state) => state }
                          // Group items and batch insert into database.
                          .groupedWithin(1000, 1000 millis)
                          .mapAsync(10) { states: Seq[ScanState] =>

                            for {
                              (a, b, d) <- session.db.run((for {
                                // Save the deltas
                                a <- Deltas ++= states.filter(_.delta.isDefined)
                                  .map(state => DeltaRow(0, ingestBundleId, state.seqId, state.micros,
                                    fmt.deltaEn(state.delta.get).pretty(Printer.noSpaces), None))
                                // Save the snapshots
                                b <- Snapshots ++= states
                                  .filter(_.snapshot.isDefined).map(state => {
                                  SnapshotRow(0, ingestBundleId, state.seqId, state.micros,
                                    fmt.modelEn(state.item).pretty(Printer.noSpaces), None)
                                })
                                // Delete snapshots from the future. Backfill related edge case.
                                d <- Snapshots
                                  .filter(_.bundle === backfillBundleId)
                                  .filter(_.micros >= states.headOption.map(_.micros))
                                  .delete
                              } yield (a, b, d)).transactionally)
                              _ = log.debug("Saved {} deltas and {} snapshots for {}", a, b, path)
                            } yield states.last.seqId
                          }
                          // Clear ingested items from buffer.
                          .runWith(Sink.foreach { lastIngestedSeqId =>
                            self ! DataIngested(ingestBundleId, lastIngestedSeqId)
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
      Option(subscriptions.get(item.path)).foreach(refs => refs.foreach(_ ! item))

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
        {
          var srcOpt: Option[Source[MarketData[T], NotUsed]] = None
          val newSubs = Option(subscriptions.computeIfPresent(path.toString, (_, subs) => {
            val (ref, _src) =
              Source.actorRef[MarketData[T]](Int.MaxValue, OverflowStrategy.fail).preMaterialize()
            srcOpt = Some(_src)
            subs + ref
          }))
          newSubs.map(subs => {
            val initialItems: Vector[MarketData[T]] = lookupBundleId(path)
              .flatMap(itemBuffers.get(_).map(_.asInstanceOf[Vector[MarketData[T]]]) )
              .getOrElse(Vector.empty[MarketData[T]])
            Source(initialItems).concat(srcOpt.get)
          })
        }
      sender ! buildOptSrc(DeltaFmt.formats(path.datatype))
  }

  def lookupBundleId(path: DataPath[_]): Option[Long] = Option(bundleIndex.get(path)).map(_.last)
}


object DataSourceActor {
  case class Init(err: Option[Throwable])
  case class Err(err: Throwable)
  case class Ingest(queue: Seq[(DataType[_], Set[String])])
  case class DataIngested(bundleId: Long, seqId: Long)
  case object Index

//  case class DataBundle(path: DataPath, bundleId: Long, begin: Long, end: Option[Long])

  // How often to save a snapshot to the db.
  val SnapshotInterval = 4 hours
}
