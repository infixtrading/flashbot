package com.infixtrading.flashbot
import java.sql.{Connection, DriverManager}

import akka.NotUsed
import akka.stream.alpakka.slick.javadsl.SlickSession
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.DeltaFmtJson
import com.infixtrading.flashbot.models.core.DataPath
import com.typesafe.config.Config
import slick.lifted.TableQuery

import scala.concurrent.Future

package object db {

  val Deltas = new TableQuery(tag => new Deltas(tag))
  val Snapshots = new TableQuery(tag => new Snapshots(tag))
  val Bundles = new TableQuery(tag => new Bundles(tag))
  val Backfills = new TableQuery(tag => new Backfills(tag))

  def createBundle(path: DataPath)(implicit session: SlickSession): Future[Long] = {
    import session.profile.api._
    val item = (0l, path.source, path.topic, path.datatype)
    session.db.run((Bundles returning Bundles.map(_.id)) += item)
  }

  def streamBundle[T](id: Long, fromMicros: Long)
                     (implicit fmt: DeltaFmtJson[T]): Source[T, NotUsed] = ???

//  def ingestItemsAsync[T](bundleId: Long, items: Seq[((Long, T), Long)])
//                         (implicit session: SlickSession, fmt: DeltaFmtJson[T]): Future[Long] = {
//    import session.profile.api._
//    var deltas = Seq.empty[(Long, Long, Long, String)]
//    var snaps = Seq.empty[(Long, Long, Long, String)]
//    for (((micros, item), index) <- items) {
//    }
//  }
}
