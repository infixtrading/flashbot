package flashbot.db

import java.sql.Timestamp

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

case class BackfillRow(bundle: Long, source: String, topic: String, datatype: String,
                       cursor: Option[String], nextPageAt: Option[Timestamp],
                       claimedBy: Option[String], claimedAt: Option[Timestamp]) {
  def path: DataPath[_] = DataPath(source, topic, DataType(datatype))
}

class Backfills(tag: Tag) extends Table[BackfillRow](tag, "flashbot_backfills") {
  def bundle = column[Long]("bundle", O.PrimaryKey)
  def source = column[String]("source")
  def topic = column[String]("topic")
  def datatype = column[String]("datatype")
  def cursor = column[Option[String]]("cursor")
  def nextPageAt = column[Option[Timestamp]]("next_page_at")
  def claimedBy = column[Option[String]]("claimed_by")
  def claimedAt = column[Option[Timestamp]]("claimed_at")

  override def * =
    (bundle, source, topic, datatype, cursor, nextPageAt, claimedBy, claimedAt) <>
      (BackfillRow.tupled, BackfillRow.unapply)

  def pk = index("pk_backfills", (source, topic, datatype))
}
