package com.infixtrading.flashbot.engine
import java.io.File

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, Props, RootActorPath, Terminated}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent._
import akka.pattern.{Backoff, BackoffSupervisor}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.DataSource.{DataSourceIndex, TopicIndex}
import com.infixtrading.flashbot.core.{DeltaFmt, DeltaFmtJson, FlashbotConfig, MarketData}
import com.infixtrading.flashbot.core.FlashbotConfig._
import com.infixtrading.flashbot.core.DeltaFmt._
import com.infixtrading.flashbot.models.core.DataPath
import com.infixtrading.flashbot.util.stream._
import com.infixtrading.flashbot.util._
import com.infixtrading.flashbot.db._
import com.infixtrading.flashbot.engine.DataSourceActor.StreamLiveData

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random

/**
  * A DataServer runs and manages a set of data sources. Every Flashbot node runs a local data
  * server but only nodes with the "data-ingest" role will actually ingest data. All DataServers
  * share the same data store. Currently this is represented as a JDBC URL. While they share
  * persistence layers, DataServers differ in the types of live data they have access to. A data
  * server only has access to live data if it manages a DataSourceActor which is ingesting that
  * data stream.
  */
object DataServer {

  /**
    * DataSelection is a description of a stream of market data.
    *
    * @param path the data path (source, topic, datatype).
    * @param from the start time of data. If none, use the current time.
    * @param to the end time of data. If none, stream indefinitely.
    */
  case class DataSelection(path: DataPath, from: Option[Long], to: Option[Long]) {
    def isPolling: Boolean = to.isEmpty
  }

  /**
    * The message every data server sends to every other one to register itself across the cluster.
    */
  case object RegisterDataServer

  /**
    * Request a data stream source from the cluster. Returns a CompressedSourceRef if the sender
    * is remote and just a Source[ MarketData[_] ] if the sender is local.
    */
  case class DataStreamReq[T](selection: DataSelection)
    extends StreamRequest[T]

  /**
    * Used internally to remove data sources that don't respond to queries in time.
    */
  case class RemoteServerTimeout(ref: ActorRef)

  case class LiveStream(path: DataPath)

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

  /**
    * Register ourselves with the remote server. This is how all DataServers in the cluster are
    * aware of one another.
    */
  def register(member: Member): Unit = {
    val remoteServer =
      context.actorSelection(RootActorPath(member.address) / "user" / "data-server")
    remoteServer ! RegisterDataServer
  }

  // Map of child DataSourceActors that this DataServer supervises.
  var localDataSourceActors: Map[String, ActorRef] = configs.map {
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
      * ===========
      *   Queries
      * ===========
      */

    /**
      * A data stream is constructed out of two parts: A historical stream, and a live component.
      * These two streams are concatenated and returned.
      */
    case DataStreamReq(s @ DataSelection(path, from, to)) =>
      val nowMicros = time.currentTimeMicros
      def buildRsp[T](implicit fmt: DeltaFmtJson[T]) = {
        for {
          // Validate selection and build the live stream response.
          (liveStartMicros, live) <- (from, to) match {
            // Match on validation failures first.
            case (None, Some(_)) => Future.failed(
              new IllegalArgumentException("Non-polling requests must specify a 'from' value."))
            case (Some(f), None) if f > nowMicros => Future.failed(
              new IllegalArgumentException("'from' value of polling request must be less than " +
                "the current time."))
            case (Some(f), Some(t)) if f > t => Future.failed(
              new IllegalArgumentException("'from' must be less than or equal to 'to'"))

            // If it's a valid polling request, search all data servers for a live stream.
            case (_, None) =>
              searchForLiveStream[T](path, self :: remoteDataServers.values.toList)
                .flatMap(_.toFut(new RuntimeException(
                  s"Unable to find live data stream for $path.")))

            // If it's not a polling request, we use an empty stream instead.
            case (_, _) => Future.successful((nowMicros, Source.empty))
          }
          liveRsp <- StreamResponse.build(live, sender)

          // Build the historical stream.
          historical <-
            from.map(fromMicros => buildHistoricalStream[T](fromMicros, liveStartMicros))
              .getOrElse(Future.successful(Source.empty))
          historicalRsp <- StreamResponse.build(historical, sender)

        } yield StreamResponse(Seq(historicalRsp, liveRsp))
      }
      buildRsp(DeltaFmt.formats(path.dataType)) pipeTo sender


    /**
      * Asks the relevant data source actor for a live stream of the path.
      * Returns None if not found.
      */
    case LiveStream(path: DataPath) =>
      if (localDataSourceActors.isDefinedAt(path.source)) {
        sender ! None
      } else {
        (localDataSourceActors(path.source) ? StreamLiveData(path)) pipeTo sender
      }


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

  /**
    * Builds a stream of data from the persisted snapshots and deltas.
    */
  def buildHistoricalStream[T](fromMicros: Long, toMicros: Long)
      : Future[Source[T, NotUsed]] = {
    
  }

  /**
    * Asynchronous linear search over all servers for a live data stream for the given path.
    */
  def searchForLiveStream[T](path: DataPath, servers: List[ActorRef])
      : Future[Option[(Long, Source[T, NotUsed])]] = servers match {

    // When no servers left to query, return None.
    case Nil => Future.successful(None)

    // Request a live data stream from the next server in the list, and recurse if not found.
    case server :: rest => for {
      rspOpt <- (server ? LiveStream(path)).mapTo[Option[(Long, Source[T, NotUsed])]]
      ret <-
        if (rspOpt.isDefined) Future.successful(rspOpt)
        else searchForLiveStream(path, rest)
    } yield ret
  }

}
