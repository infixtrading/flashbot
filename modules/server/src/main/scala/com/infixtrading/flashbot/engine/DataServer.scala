package com.infixtrading.flashbot.engine

import java.io.File

import akka.NotUsed
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, ActorSelection, OneForOneStrategy, Props, RootActorPath, SupervisorStrategy}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent.{InitialStateAsEvents, MemberUp}
import akka.pattern._
import akka.stream.{ActorMaterializer, SourceRef}
import akka.stream.scaladsl.{Source, StreamRefs}
import akka.util.Timeout
import com.infixtrading.flashbot.core.DataSource._
import com.infixtrading.flashbot.core.{DeltaFmt, DeltaFmtJson, MarketData}
import com.infixtrading.flashbot.core.DeltaFmt._
import com.infixtrading.flashbot.core.FlashbotConfig.{DataSourceConfig, ExchangeConfig, IngestConfig}
import com.infixtrading.flashbot.util.stream._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * A DataServer runs and manages a set of data sources. Every Flashbot node runs a local data
  * server but only nodes with the "data-ingest" role will actually ingest data. It can build
  * indexes for all available data, local or remote. It also responds to data requests with a
  * StreamResponse.
  *
  * Prior to streaming data in response to a data request, we build an index of all data available
  * cluster-wide. This lets us know what data servers exist, what bundle ids they contain, and the
  * time bounds of each bundle. This is enough information to know who to ask for what, in order
  * to fulfill the data request to the best of our ability.
  *
  * Specifically, an IndexedDeltaLog can scan only one continuous data set (i.e. bundle) per call.
  * If we requested a month's worth of data from the server, but there's a day-long gap somewhere
  * in the middle of it, we will need to make two consecutive scans using IndexedDeltaLog: One for
  * each bundle.
  */
object DataServer {

  // The message every data server sends to every other one to register itself across the cluster.
  case object RegisterDataServer

  // Used as a top level request to get an index of the whole cluster.
  case object ClusterDataIndexReq

  // Used to ask another data server what data it can serve using it's local data.
  case object LocalDataIndexReq

  // Used internally to remove data sources that don't respond to queries in time.
  case class RemoteServerTimeout(ref: ActorRef)

  // Request a data stream source from the cluster. Returns a CompressedSourceRef if the sender
  // is remote and just a Source[MarketData[_]] if the sender is local.
  case class DataStreamReq[T](selection: StreamSelection, locality: Locality)
    extends StreamRequest[T]

  /**
    * When requesting data from a server, we specify if we want the server to use data available
    * on the entire cluster, or only whatever it has locally.
    */
  sealed trait Locality
  case object DiskLocality extends Locality
  case object ClusterLocality extends Locality

  case class DataSourceTerminated(key: String)
}

class DataServer(marketDataPath: File,
                 configs: Map[String, DataSourceConfig],
                 exchangeConfigs: Map[String, ExchangeConfig],
                 ingestConfig: Option[IngestConfig],
                 useCluster: Boolean) extends Actor with ActorLogging {
  import DataServer._
  import DataSourceActor._

  implicit val mat = ActorMaterializer()(context)
  implicit val ec: ExecutionContext = context.system.dispatcher

  println("data server")

  // Subscribe to cluster MemberUp events to register ourselves with all other data servers.
  val cluster: Option[Cluster] = if (useCluster) Some(Cluster(context.system)) else None
  override def preStart() = {
    if (cluster.isDefined) {
      cluster.get.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
    }
  }
  override def postStop() = {
    if (cluster.isDefined) cluster.get.unsubscribe(self)
  }

  def register(member: Member): Unit = {
    val remoteServer =
      context.actorSelection(RootActorPath(member.address) / "user" / "data-server")
    remoteServer ! RegisterDataServer
  }

  // Map of child DataSourceActors that this DataServer supervises.
  var localDataSourceActors = configs.map {
    case (key, config) =>
      // Create child DataSource actor with custom back-off supervision.
      val props = BackoffSupervisor.props(Backoff.onFailure(
        Props(new DataSourceActor(marketDataPath, key, config,
          exchangeConfigs.get(key), ingestConfig)),
        key,
        minBackoff = 1 second,
        maxBackoff = 1 minute,
        randomFactor = 0.2,
        maxNrOfRetries = 60
      ).withAutoReset(30 seconds))

      val ref = context.actorOf(props, key)

      // When/if the superviser stops, we should alert.
      context.watchWith(ref, DataSourceTerminated(key))

      key -> ref
  }

  var remoteDataServers = Map.empty[ActorPath, ActorRef]

  implicit val timeout = Timeout(10 seconds)

  override def receive = {

    /**
      * Serve the data stream request using only data that's locally available on this node.
      * Path cannot be a pattern.
      */
    case DataStreamReq(selection, DiskLocality) =>
      (localDataSourceActors(selection.path.source) ? StreamMarketData(selection))
        .mapTo[StreamResponse[MarketData[_]]]
        .flatMap(_.rebuild) pipeTo sender

    /**
      * Serve a single concrete data stream request (path cannot be a pattern) using any data
      * available in the cluster.
      */
    case DataStreamReq(selection, ClusterLocality) =>
      def buildRsp[T](implicit fmt: DeltaFmt[T]): Future[SourceRspList[T]] = {
        for {
          // Get the cluster index
          index <- (self ? ClusterDataIndexReq).collect {
            case idx: DataClusterIndex => idx
          }

          // Build the query plan
          plan = index.planQuery(self, selection)

          // Execute the plan. Get stream responses from all relevant data servers.
          futResponses = plan.map { clusterSelection =>
            val req = DataStreamReq[T](clusterSelection.toLocal, DiskLocality)
            val ref = remoteDataServers(clusterSelection.address)
            ref <<? req
          }

          // Build the StreamResponse
          responses <- Future.sequence(futResponses)
        } yield StreamResponse(responses)
      }
      buildRsp(selection.path.dataType.fmt) pipeTo sender

    /**
      * Return an index of the whole cluster.
      */
    case ClusterDataIndexReq =>
      (for {
        // Build local index
        localClusterIndex <- localDataIndex.map(DataClusterIndex(self.path, _))
        _ = { log.debug("DataSource actors: {}", localDataSourceActors.keySet.mkString(", ") )}
        _ = { log.debug("Local index size: {}", localClusterIndex.slices.size )}

        // Get remote indices
        remoteIndices <- Future.sequence(remoteDataServers.values.toSet.map((ref: ActorRef) =>
          (ref ? LocalDataIndexReq).map {
            case index: DataSourceIndex => DataClusterIndex(ref.path, index)
          } recover {
            case _: AskTimeoutException =>
              self ! RemoteServerTimeout(ref)
              DataClusterIndex.empty
          }))

        // Merge
        mergedClusterIndex = (remoteIndices + localClusterIndex).reduce(_.merge(_))

      } yield mergedClusterIndex) pipeTo sender

    /**
      * Return an index of local disk data.
      */
    case LocalDataIndexReq =>
      localDataIndex pipeTo sender

    /**
      * Cluster management messages
      */
    case MemberUp(member) => register(member)
    case RemoteServerTimeout(ref) =>
      log.warning(s"Remote data server (${ref.path}) timed out. Removing from index.")
      remoteDataServers -= ref.path
    case RegisterDataServer =>
      remoteDataServers += (sender.path -> sender)

    /**
      * Alert, log, or do something when a DataSource is terminated.
      */
    case DataSourceTerminated(key) =>
      localDataSourceActors -= key
      log.error(s"The $key DataSource has been stopped!")
  }

  def localDataIndex: Future[DataSourceIndex] = {
    Future.sequence(localDataSourceActors.map {
        case (key, ref) =>
          (ref ? Index).map { case index: TopicIndex => key -> index }
      }).map(_.toMap)
  }

  /**
    * Scan to concatenate MarketData streams (which may themselves already be heterogenous) into
    * a single heterogenous MarketData stream by setting the `bundleIndex` field. The bundle index
    * needs to be strictly increasing per stream, and this method makes sure that happens. Merging
    * market data streams without this method is generally not supported. Here we keep track of
    * how many separate bundles we've seen so far. This running count is the new `bundleIndex`.
    */
  def concatMarketDataStreams[T](sources: List[Source[MarketData[T], NotUsed]]): Source[MarketData[T], NotUsed] =
    Source(sources)
      .zipWithIndex
      .flatMapConcat { case (src, i) => src.map(md => (s"${i}_${md.bundleIndex}", md)) }

      .scan[(Long, Option[(String, MarketData[T])])]((0, None)) {
        // Base case
        case ((idx, None), (uid, md)) => (idx, Some((uid, md)))

        // If the current uid is not the same as the previous one, then we've detected a new
        // bundle. Either within the same original stream, or a new stream has started. It makes
        // no difference here, we are overwriting the `bundleIndex` of the entire stream to our
        // version of it.
        case ((idx, Some((prevUid, _))), (uid, md)) if prevUid != uid =>
          (idx + 1, Some((uid, md)))

        // Otherwise, the uid stayed the same, so should our running bundleIndex.
        case ((idx, Some((prevUid, _))), (uid, md)) if prevUid == uid =>
          (idx, Some((uid, md)))
      }
      .collect {
          // Filter our the base case and copy over our custom bundle index to the stream.
          case (idx, Some((_, md))) => md.withBundle(idx.toInt)
      }

}
