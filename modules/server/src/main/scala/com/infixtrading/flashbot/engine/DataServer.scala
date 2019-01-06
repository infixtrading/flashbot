package com.infixtrading.flashbot.engine
import java.io.File

import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, Props, RootActorPath, Terminated}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent._
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.pattern.{ask, pipe}
import com.infixtrading.flashbot.core.DataSource.{DataSourceIndex, TopicIndex}
import com.infixtrading.flashbot.core.{DeltaFmt, FlashbotConfig, MarketData}
import com.infixtrading.flashbot.core.FlashbotConfig._
import com.infixtrading.flashbot.core.DeltaFmt._
import com.infixtrading.flashbot.models.core.DataPath
import com.infixtrading.flashbot.util.stream._
import com.infixtrading.flashbot.db._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

/**
  * A DataServer runs and manages a set of data sources. Every Flashbot node runs a local data
  * server but only nodes with the "data-ingest" role will actually ingest data. It can build
  * indexes for all available data, local or remote. It also responds to data requests with a
  * StreamResponse.
  */

object DataServer {

  /**
    * DataSelection is a query for a stream of market data.
    *
    * @param path the data path (source, topic, datatype).
    * @param from the start time of data. If none, use the current time.
    * @param to the end time of data. If none, stream indefinitely.
    */
  case class DataSelection(path: DataPath, from: Option[Long], to: Option[Long]) {
    def isPolling: Boolean = to.isEmpty
  }

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
  case class DataStreamReq[T](selection: DataSelection, locality: Locality)
    extends StreamRequest[T]

  /**
    * When requesting data from a server, we specify if we want the server to use data available
    * on the entire cluster, or only whatever it has locally.
    */
  sealed trait Locality
  case object DiskLocality extends Locality
  case object ClusterLocality extends Locality

  case class DataBundle(path: DataPath, bundleId: Long, begin: Long, end: Option[Long])

  case class DataSourceSupervisorTerminated(key: String)

  case class RemoteServerTerminated(ref: ActorRef)

  def props: Props = {
    val fbConfig = FlashbotConfig.load
    Props(new DataServer(
      fbConfig.`jdbc-url`,
      fbConfig.sources,
      fbConfig.exchanges,
      ingestConfig = None,
      useCluster = false))
  }
}


class DataServer(jdbcUrl: String,
                 configs: Map[String, DataSourceConfig],
                 exchangeConfigs: Map[String, ExchangeConfig],
                 ingestConfig: Option[IngestConfig],
                 useCluster: Boolean) extends Actor with ActorLogging {
  import DataServer._
  import DataSourceActor._

  implicit val mat = ActorMaterializer()(context)
  implicit val ec: ExecutionContext = context.system.dispatcher

  val random = new Random()

  // Create SQL tables on start.
  createSnapshotsTableIfNotExists(jdbcUrl)
  createDeltasTableIfNotExists(jdbcUrl)

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
        Props(new DataSourceActor(jdbcUrl, key, config,
          exchangeConfigs.get(key), ingestConfig)),
        key,
        minBackoff = 1 second,
        maxBackoff = 1 minute,
        randomFactor = 0.2,
        maxNrOfRetries = 60
      ).withAutoReset(30 seconds))

      // When/if the supervisor stops, we should alert.
      val ref = context.actorOf(props, key)
      context.watchWith(ref, DataSourceSupervisorTerminated(key))

      key -> ref
  }

  var remoteDataServers = Map.empty[ActorPath, ActorRef]

  implicit val timeout = Timeout(10 seconds)

  def receive = {
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
      * available in the cluster. First we have to get an index of the entire cluster. The index
      * is a collection of all bundles in the cluster, keyed by ActorRef. Each bundle contains the
      * data path, bundle id, start time, and optional end time. End time is missing if the
      * bundle is currently being ingested and does not have a defined end time yet.
      */
    case DataStreamReq(selection, ClusterLocality) =>
      def buildRsp[T](implicit fmt: DeltaFmt[T]): Future[SourceRspList[T]] = {
        for {
          // Get the cluster index, find the best data server to issue a DiskLocality data
          // request to.
          index <- (self ? ClusterDataIndexReq).mapTo[Map[ActorRef, Set[DataBundle]]]
          refScores = index.mapValues(_.map(scoreBundle(selection, _)).max)
          bestScore = refScores.values.toSet.max
          candidates <- {
            val cs = refScores.filter(_._2 == bestScore).keySet
            if (cs.isEmpty)
              Future.failed(new IllegalStateException("Unable to find data source candidates."))
            else Future.successful(cs)
          }

          // If this data server is among the candidates, use it.
          // Otherwise, choose a random one.
          bestDataServer =
            if (candidates.contains(self)) self
            else candidates.toSeq(random.nextInt(candidates.size))

          // Stream the data from the chosen data server.
          rsp <- bestDataServer <<? DataStreamReq[T](selection, DiskLocality)
        } yield StreamResponse(Seq(rsp))
      }
      buildRsp(selection.path.dataType.fmt) pipeTo sender

    /**
      * Respond with an index of the whole cluster.
      */
    case ClusterDataIndexReq =>

    /**
      * Respond with an index of the local data sources.
      */
    case LocalDataIndexReq =>
      localDataIndex pipeTo sender


    /**
      * ===============================
      *   Cluster management messages
      * ===============================
      */

    case MemberUp(member) =>
      register(member)

    case RegisterDataServer =>
      // Subscribes to the death watch for the registering data server.
      context.watchWith(sender, RemoteServerTerminated(sender))
      // Save the registering data server.
      remoteDataServers += (sender.path -> sender)

    case RemoteServerTerminated(server) =>
      remoteDataServers -= server.path

    case DataSourceSupervisorTerminated(key) =>
      localDataSourceActors -= key
      log.error(s"The $key DataSource has been stopped!")

  }

  def localDataIndex: Future[Set[DataBundle]] = {
    Future.sequence(localDataSourceActors.map {
      case (key, ref) => (ref ? Index).mapTo[Set[DataBundle]]
    }).map(_.toSet.flatten)
  }

  def scoreBundle(selection: DataSelection, bundle: DataBundle): Double = ???

}
