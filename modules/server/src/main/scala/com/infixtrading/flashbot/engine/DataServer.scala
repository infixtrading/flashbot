package com.infixtrading.flashbot.engine

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorLogging, ActorPath, ActorRef, Props, RootActorPath}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member}
import akka.pattern.{Backoff, BackoffSupervisor, ask, pipe}
import akka.stream.ActorMaterializer
import akka.stream.alpakka.slick.scaladsl._
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import com.infixtrading.flashbot.core.FlashbotConfig._
import com.infixtrading.flashbot.core.MarketData.BaseMarketData
import com.infixtrading.flashbot.core.{DeltaFmt, DeltaFmtJson, FlashbotConfig, MarketData}
import com.infixtrading.flashbot.db._
import com.infixtrading.flashbot.engine.DataSourceActor.StreamLiveData
import com.infixtrading.flashbot.models.core.{DataPath, TimeRange}
import com.infixtrading.flashbot.util._
import com.infixtrading.flashbot.util.stream._
import com.typesafe.config.Config
import io.circe.parser._
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.meta.MTable

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Random, Success}

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
  case class DataSelection(path: DataPath, from: Option[Long] = None, to: Option[Long] = None) {
    def isPolling: Boolean = to.isEmpty
    def timeRange: Option[TimeRange] =
      for {
        f <- from
        t <- to
      } yield TimeRange(f, t)

  }

  /**
    * The message every data server sends to every other one to register itself across the cluster.
    */
  case object RegisterDataServer

  /**
    * Request a data stream source from the cluster. Returns a [[CompressedSourceRef]] if the sender
    * is remote and just a Source[ MarketData[_] ] if the sender is local.
    */
  case class DataStreamReq[T](selection: DataSelection) extends StreamRequest[T]

  /**
    * Used internally to remove data sources that don't respond to queries in time.
    */
  case class RemoteServerTimeout(ref: ActorRef)

  case class LiveStream(path: DataPath)

  case class DataSourceSupervisorTerminated(key: String)

  case class RemoteServerTerminated(ref: ActorRef)

  def props: Props = props(FlashbotConfig.load)
  def props(config: FlashbotConfig): Props = {
    Props(new DataServer(
      config.db,
      config.sources,
      config.exchanges,
      ingestConfig = None,
      useCluster = false))
  }

  sealed trait Wrap extends Any {
    def micros: Long
    def isSnap: Boolean
    def data: String
    def bundle: Long
    def seqid: Long
  }
  class SnapshotWrap(val cols: (Long, Long, Long, String)) extends AnyVal with Wrap {
    override def micros = cols._3
    override def isSnap = true
    override def data = cols._4
    override def bundle = cols._1
    override def seqid = cols._2
  }
  class DeltaWrap(val cols: (Long, Long, Long, String)) extends AnyVal with Wrap {
    override def micros = cols._3
    override def isSnap = false
    override def data = cols._4
    override def bundle = cols._1
    override def seqid = cols._2
  }

  object Wrap {
    val ordering: Ordering[Wrap] = new Ordering[Wrap] {
      override def compare(x: Wrap, y: Wrap) = {
        if (x.micros < y.micros) -1
        else if (x.micros > y.micros) 1
        else if (x.isSnap && !y.isSnap) -1
        else if (!x.isSnap && y.isSnap) 1
        else 0
      }
    }
  }

  class BundleRow(val cols: (Long, String, String, String)) extends AnyVal {
    def id = cols._1
    def source = cols._2
    def topic = cols._3
    def datatype = cols._4
  }

}

class DataServer(dbConfig: Config,
                 configs: Map[String, DataSourceConfig],
                 exchangeConfigs: Map[String, ExchangeConfig],
                 ingestConfig: Option[IngestConfig],
                 useCluster: Boolean) extends Actor with ActorLogging {
  import DataServer._

  implicit val mat = ActorMaterializer()(context)
  implicit val ec: ExecutionContext = context.system.dispatcher

  val random = new Random()

  val slickSession = SlickSession.forConfig(dbConfig)

  // Subscribe to cluster MemberUp events to register ourselves with all other data servers.
  val cluster: Option[Cluster] = if (useCluster) Some(Cluster(context.system)) else None
  override def preStart() = {
    if (cluster.isDefined) {
      cluster.get.subscribe(self, initialStateMode = InitialStateAsEvents, classOf[MemberUp])
    }
  }
  override def postStop() = {
    if (cluster.isDefined) cluster.get.unsubscribe(self)
    slickSession.close()
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

  // Create SQL tables on start.
  val _tables = List(Bundles, Snapshots, Deltas)
  Await.result(for {
    existingTables <- slickSession.db.run(MTable.getTables)
    names = existingTables.map(_.name.name)
    _ = { log.debug("Existing tables: {}", names) }
    _ <- slickSession.db.run(DBIO.sequence(_tables.filter(table =>
      !names.contains(table.baseTableRow.tableName) ).map(x => {
      log.debug("Creating table: {}", x.baseTableRow.tableName)
      x.schema.create
    })))
  } yield Done, 5 seconds) match {
    case Done =>
      log.debug("SQL tables ready")
  }


  // Map of child DataSourceActors that this DataServer supervises.
  var localDataSourceActors: Map[String, ActorRef] = configs.map {
    case (key, config) =>
      // Create child DataSource actor with custom back-off supervision.
      val props = BackoffSupervisor.props(Backoff.onFailure(
        Props(new DataSourceActor(
          slickSession, key, config, exchangeConfigs.get(key), ingestConfig)),
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
      * These two streams are concatenated, deduped, and returned.
      */
    case DataStreamReq(DataSelection(path, from, to)) =>
      val nowMicros = time.currentTimeMicros
      val isLive = to.isEmpty
      log.debug("Received data stream request")
      def buildRsp[T](implicit fmt: DeltaFmtJson[T]): Future[StreamResponse[MarketData[T]]] = {
        val src: Future[Source[MarketData[T], NotUsed]] = for {
          // Validate selection and build the live stream response.
          live <- (from, to) match {
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
              log.debug("Searching for live stream")
              searchForLiveStream[T](path, self :: remoteDataServers.values.toList)
                .flatMap(_.toFut(new RuntimeException(
                  s"Unable to find live data stream for $path.")))
                .map(_.toSource)

            // If it's not a polling request, we use an empty stream instead.
            case (_, _) => Future.successful(Source.empty)
          }

          _ = { log.debug("Found live stream") }

          // Build the historical stream.
          historical <- from
            .map(buildHistoricalStream[T](path, _, to.getOrElse(Long.MaxValue)))
            .getOrElse(Future.successful(Source.empty))

          _ = { log.debug("Built historical stream") }

        } yield
          // If is live, concat historical with live. Drop unordered to account for any overlap
          // in the two streams.
          if (isLive)
            historical.concat(live).via(dropUnordered(MarketData.orderBySequence[T]))

          // Historical queries do not have a live component.
          else historical

        src.flatMap(StreamResponse.build[MarketData[T]](_, sender))
      }

      buildRsp(DeltaFmt.formats(path.datatype)) pipeTo sender


    /**
      * Asks the relevant data source actor for a live stream of the path.
      * Returns None if not found. Do not use search here. Search uses this.
      */
    case LiveStream(path: DataPath) =>
      if (!localDataSourceActors.isDefinedAt(path.source)) {
        sender ! None
      } else {
        def buildSrc[T](implicit fmt: DeltaFmtJson[T]) = for {
          srcOpt: Option[Source[T, NotUsed]] <-
            (localDataSourceActors(path.source) ? StreamLiveData(path))
              .mapTo[Option[Source[T, NotUsed]]]
          rspFutOpt: Option[Future[Option[StreamResponse[T]]]] =
            srcOpt.map(x => StreamResponse.build(x, sender).map(Some(_)))
          rspOpt <- rspFutOpt.getOrElse(Future.successful(None))
        } yield rspOpt
        buildSrc(DeltaFmt.formats(path.datatype)) pipeTo sender
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
    *
    * @param fromMicros inclusive start time in epoch micros.
    * @param toMicros exclusive end time in epoch micros.
    */
  def buildHistoricalStream[T](path: DataPath, fromMicros: Long, toMicros: Long)
                              (implicit fmt: DeltaFmtJson[T])
      : Future[Source[MarketData[T], NotUsed]] = {
    implicit val session = SlickSession.forConfig(dbConfig)
    val lookbackFromMicros = fromMicros - DataSourceActor.SnapshotInterval.toMicros
    log.debug("Building historical stream")
    for {
      bundles <- Slick.source(Bundles
        .filter(b => b.source === path.source &&
          b.topic === path.topic && b.datatype === path.datatype)
        .result).map(new BundleRow(_)).runWith(Sink.seq)

      snapshots: Source[Wrap, NotUsed] = Slick
        .source(Snapshots
          .filter(x => (x.micros >= lookbackFromMicros) && (x.micros < toMicros))
          .filter(x => x.bundle.inSet(bundles.map(_.id)))
          .result)
        .map(new SnapshotWrap(_))

      deltas: Source[Wrap, NotUsed] = Slick
        .source(Deltas
          .filter(x => (x.micros >= lookbackFromMicros) && (x.micros < toMicros))
          .filter(x => x.bundle.inSet(bundles.map(_.id)))
          .result)
        .map(new DeltaWrap(_))

    } yield snapshots
      .mergeSorted[Wrap, NotUsed](deltas)(Wrap.ordering)
      .scan[Option[MarketData[T]]](None) {
        /**
          * Base case.
          */
        case (None, wrap) if wrap.isSnap =>
          Some(BaseMarketData(
            decode[T](wrap.data)(fmt.modelDe).right.get,
            path, wrap.micros, wrap.bundle, wrap.seqid))

        /**
          * If it's a snapshot from this or a later bundle, use its value.
          */
        case (Some(md), wrap) if wrap.isSnap && wrap.bundle >= md.bundle =>
          Some(BaseMarketData(
            decode[T](wrap.data)(fmt.modelDe).right.get,
            path, wrap.micros, wrap.bundle, wrap.seqid))

        /**
          * If it's a snapshot from an outdated bundle, ignore.
          */
        case (Some(md), wrap) if wrap.isSnap => Some(md)

        /**
          * No data yet. Delta has no effect.
          */
        case (None, wrap) if !wrap.isSnap => None

        /**
          * Delta has effect if it's the same bundle and next sequence id.
          */
        case (Some(md: MarketData[_]), wrap)
            if !wrap.isSnap && wrap.bundle == md.bundle && wrap.seqid == md.seqid + 1 =>
          val delta = decode[fmt.D](wrap.data)(fmt.deltaDe).right.get
          Some(BaseMarketData(fmt.update(md.data, delta),
            md.path, wrap.micros, wrap.bundle, wrap.seqid))

        /**
          * Delta belongs to another bundle or not the next seqnr, ignore.
          */
        case (Some(x), wrap) if !wrap.isSnap => Some(x)
      }
        .collect { case Some(value) => value }
        .via(deDupeBy(md => (md.bundle, md.seqid)))
        .dropWhile(_.micros < fromMicros)
  }

  /**
    * Asynchronous linear search over all servers for a live data stream for the given path.
    */
  def searchForLiveStream[T](path: DataPath, servers: List[ActorRef])
      : Future[Option[StreamResponse[MarketData[T]]]] = servers match {

    // When no servers left to query, return None.
    case Nil => Future.successful(None)

    // Request a live data stream from the next server in the list, and recurse if not found.
    case server :: rest => for {
      rspOpt <- (server ? LiveStream(path))
        .mapTo[Option[StreamResponse[MarketData[T]]]]
      ret <- if (rspOpt.isDefined) Future.successful(rspOpt)
        else searchForLiveStream[T](path, rest)
    } yield ret
  }
}
