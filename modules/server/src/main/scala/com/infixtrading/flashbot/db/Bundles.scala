package com.infixtrading.flashbot.db

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

case class BundleRow(id: Long, source: String, topic: String, datatype: String)

class Bundles(tag: Tag) extends Table[BundleRow](tag, "flashbot_bundles") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def source = column[String]("source")
  def topic = column[String]("topic")
  def datatype = column[String]("datatype")
  override def * = (id, source, topic, datatype) <> (BundleRow.tupled, BundleRow.unapply)
}
