package flashbot
import java.sql.{Connection, DriverManager}

import akka.NotUsed
import akka.stream.alpakka.slick.javadsl.SlickSession
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import flashbot.core.DeltaFmtJson
import flashbot.db._
import flashbot.models.core.DataPath
import slick.lifted.TableQuery

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

package object db {

  sealed trait Wrap extends Any {
    def micros: Long
    def isSnap: Boolean
    def data: String
    def bundle: Long
    def seqid: Long
    def backfill: Option[Long]
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

  case class DeltaRow(id: Long, bundle: Long, seqid: Long, micros: Long,
                      data: String, backfill: Option[Long]) extends Wrap {
    def isSnap = false
  }
  case class SnapshotRow(id: Long, bundle: Long, seqid: Long, micros: Long,
                         data: String, backfill: Option[Long]) extends Wrap {
    def isSnap = true
  }


  val Deltas = new TableQuery(tag => new Deltas(tag))
  val Snapshots = new TableQuery(tag => new Snapshots(tag))
  val Bundles = new TableQuery(tag => new Bundles(tag))
  val Backfills = new TableQuery(tag => new Backfills(tag))

  val Tables = List(Bundles, Snapshots, Deltas, Backfills)

  implicit class BundleOps(query: TableQuery[Bundles])
                          (implicit session: SlickSession) {
    import session.profile.api._

    def forPath(path: DataPath[_]) =
      query.filter(row => row.source === path.source &&
        row.topic === path.topic &&
        row.datatype === path.datatype.name)
  }

  implicit class BackfillOps(query: TableQuery[Backfills])
                            (implicit session: SlickSession) {
    import session.profile.api._

    def forPath(path: DataPath[_]) =
      query.filter(row => row.source === path.source &&
        row.topic === path.topic &&
        row.datatype === path.datatype.name)
  }

  implicit class SnapshotOps(query: TableQuery[Snapshots])
                            (implicit session: SlickSession){
    import session.profile.api._

    def forPath(path: DataPath[_]) =
      for {
        bundleId <- Bundles.forPath(path).map(_.id)
        s <- Snapshots if bundleId === s.bundle
      } yield s
  }

  implicit class DeltaOps(query: TableQuery[Deltas])
                            (implicit session: SlickSession){
    import session.profile.api._

    def forPath(path: DataPath[_]) =
      for {
        bundleId <- Bundles.forPath(path).map(_.id)
        d <- Deltas if bundleId === d.bundle
      } yield d
  }


  def createBundles(path: DataPath[_])(implicit session: SlickSession): Future[(Long, Long)] = {
    import session.profile.api._
    val item = BundleRow(0l, path.source, path.topic, path.datatype.toString)
    session.db.run((Bundles returning Bundles.map(_.id)) ++= Seq(item, item))
      .map(ids => (ids.head, ids.last))
  }
}
