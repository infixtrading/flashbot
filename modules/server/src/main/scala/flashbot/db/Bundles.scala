package flashbot.db

import flashbot.core.DataType
import flashbot.models.core.DataPath
import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

case class BundleRow(id: Long, source: String, topic: String, datatype: String) {
  def path: DataPath[_] = DataPath(source, topic, DataType(datatype))
}

class Bundles(tag: Tag) extends Table[BundleRow](tag, "flashbot_bundles") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def source = column[String]("source")
  def topic = column[String]("topic")
  def datatype = column[String]("datatype")
  override def * = (id, source, topic, datatype) <> (BundleRow.tupled, BundleRow.unapply)
}