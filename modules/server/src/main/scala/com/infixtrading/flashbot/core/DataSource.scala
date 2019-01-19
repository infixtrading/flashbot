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
import com.infixtrading.flashbot.core.FlashbotConfig.ExchangeConfig
import com.infixtrading.flashbot.models.core.Slice.SliceId
import com.infixtrading.flashbot.models.core.{DataAddress, DataPath, Slice, TimeRange}
import com.infixtrading.flashbot.util.time.parseDuration
import com.infixtrading.flashbot.util.stream._

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

abstract class DataSource {

  def discoverTopics(exchangeConfig: Option[ExchangeConfig])
                    (implicit ctx: ActorContext, mat: ActorMaterializer): Future[Set[String]] =
    Future.successful(exchangeConfig.flatMap(_.pairs)
      .map(_.map(_.toString).toSet).getOrElse(Set.empty))

  def types: Map[String, DeltaFmtJson[_]] = Map.empty

  def scheduleIngest(topics: Set[String], dataType: String): IngestSchedule =
    IngestOne(topics.head, 0 seconds)

  def ingestGroup[T](topics: Set[String], datatype: DataType[T])
                    (implicit ctx: ActorContext, mat: ActorMaterializer)
      : Future[Map[String, Source[(Long, T), NotUsed]]] =
    Future.failed(new NotImplementedError("ingestGroup is not implemented by this data source."))

  def ingest[T](topic: String, datatype: DataType[T])
               (implicit ctx: ActorContext, mat: ActorMaterializer)
      : Future[Source[(Long, T), NotUsed]] =
    Future.failed(new NotImplementedError("ingest is not implemented by this data source."))
}

object DataSource {

  sealed trait IngestSchedule {
    def delay: Duration
  }
  final case class IngestGroup(topics: Set[String], delay: Duration) extends IngestSchedule
  final case class IngestOne(topic: String, delay: Duration) extends IngestSchedule

  case class Bundle(id: Long, fromMicros: Long, toMicros: Long)
}

