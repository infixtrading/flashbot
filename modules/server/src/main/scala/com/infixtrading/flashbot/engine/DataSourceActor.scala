package com.infixtrading.flashbot.engine

import java.io.File
import java.util.concurrent.Executors

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.pattern.pipe
import io.circe.Json
import com.infixtrading.flashbot.core._
import com.infixtrading.flashbot.core.DataSource._
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.models.core.Slice.SliceId
import com.infixtrading.flashbot.core.FlashbotConfig._
import com.infixtrading.flashbot.models.core.DataPath
import com.infixtrading.flashbot.util.time._
import com.infixtrading.flashbot.util.stream._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * An actor that runs a single instance of a data source. Supervised by DataServer.
  * It answers requests for data by sending out a StreamWrap that's ready to send data.
  * It is also indexes the data that it ingests, so that it can answer queries about what
  * data exists for what time frames.
  */
object DataSourceActor {
  case class Init(err: Option[Throwable])
  case class Err(err: Throwable)
  case class Ingest(queue: Seq[(String, Set[String])])
  case object Index

  case class StreamMarketData[T](selection: StreamSelection,
                                 matchId: SliceId = SliceId.wildcard)
    extends StreamRequest[T]
}

class DataSourceActor(marketDataPath: File,
                      srcKey: String,
                      config: DataSourceConfig,
                      exchangeConfig: Option[ExchangeConfig],
                      ingestConfig: Option[IngestConfig]) extends Actor with ActorLogging {
  import DataSourceActor._

  implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(100))
  implicit val system = context.system
  implicit val mat = ActorMaterializer()

  val snapshotInterval = 4 hours

  val cls = getClass.getClassLoader.loadClass(config.`class`)
//  val constructor = cls.getConstructor(classOf[Map[String, DataTypeConfig]])
  val constructor = cls.getConstructor()

  val dataSource = constructor
    .newInstance()
    .asInstanceOf[DataSource]

  val types: Map[String, DeltaFmtJson[_]] =
    config.datatypes.getOrElse(Seq.empty).foldLeft(dataSource.types)((memo, dt) =>
      memo + (dt -> DataType.parse(dt).get.fmtJson))

  val topicsFut = dataSource.discoverTopics(exchangeConfig)

  val pathsFut: Future[Set[DataPath]] = topicsFut.map(topics =>
    topics.flatMap(topic => types.keySet.map(dt => DataPath(srcKey, topic, dt))))

  // Initialize ingest when data is loaded
  pathsFut.andThen {
    case Success(_) => self ! Init(None)
    case Failure(err) => self ! Init(Some(err))
  }

  def ingestMsgOpt(queue: Seq[(String, Set[String])]): Option[Ingest] = {
    val filteredQueue = queue.filter(_._2.nonEmpty)
    if (filteredQueue.nonEmpty) Some(Ingest(filteredQueue)) else None
  }

  def matchers: Set[String] = ingestConfig.get.paths.toSet

  override def receive = {

    case Init(Some(err)) => throw err
    case Err(err) => throw err

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
          def getStreams[T](topics: Either[String, Set[String]], delay: Duration,
                            matchers: Set[DataPath])(implicit fmt: DeltaFmtJson[T]) = for {
            _ <- Future { Thread.sleep(delay.toMillis) }

            streams <- topics match {
              case Left(topic) => dataSource.ingest(topic, fmt).map(src => Map(topic -> src))
              case Right(ts) => dataSource.ingestGroup(ts, fmt)
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

                // Run the source for each topic and save it to disk.
                sources.foreach {
                  case (topic, source) =>
                    val deltaLog = iDeltaLog[T](topic)
                    log.debug("Saving data stream {}/{}/{}", srcKey, topic, dataType)
                    source.runForeach {
                      case (micros, data) =>
                        deltaLog.save(micros, data)
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
      * Respond with a TopicIndex to whoever is asking.
      */
    case Index =>
      // Create an IndexedDeltaLog for every possible combination of topic + dataType. This
      // ensures our index is complete. Many of the ephemeral IndexedDeltaLogs will be empty,
      // but that's ok.
      pathsFut.map(paths => {
        TopicIndex.build(paths.groupBy(_.topic).map {
          case (topic, paths2) =>
            topic -> DataTypeIndex.build(paths2.groupBy(_.dataType).map {
              case (dt, paths3) =>
                dt -> SliceIndex.build(paths3.toSeq.flatMap(a =>
                  iDeltaLog(a.topic)(types(a.dataType)).index.slices))
            })
        })
      }) pipeTo sender


    /**
      * Returns a SourceRspList of MarketData. One stream for each bundle matched.
      */
    case StreamMarketData(selection @ StreamSelection(path, from, to, polling), sliceMatch) =>
      def buildRsp[T](implicit fmt: DeltaFmtJson[T]): Future[SourceRspList[MarketData[T]]] =
        StreamResponse.buildList(bundleStreams(path.topic, from, to, polling, sliceMatch), sender)
      buildRsp(types(path.dataType)) pipeTo sender
  }

  def bundleStreams[T](topic: String, fromMicros: Long, toMicros: Long,
                       polling: Boolean, sliceMatch: SliceId)
                      (implicit fmt: DeltaFmtJson[T]): List[Source[MarketData[T], NotUsed]] =
    iDeltaLog(topic)
      .scan(sliceMatch.matches, fromMicros, toMicros, polling)
      .toList.zipWithIndex
      .map { case (it, bundle) =>
        it.map { case (data, micros) =>
          BaseMarketData[T](data, DataPath(srcKey, topic, fmt.fmtName), micros, bundle)
        }.toSource
      }


  def iDeltaLog[T](topic: String)(implicit fmt: DeltaFmtJson[T]): IndexedDeltaLog[T] =
    new IndexedDeltaLog[T](
      new File(marketDataPath, s"$srcKey/$topic/${fmt.fmtName}"),
      ingestConfig.map(_.retention), 4 hours)
}
