package com.infixtrading.flashbot.db

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

class Bundles(tag: Tag) extends Table[(Long, String, String, String)](tag, "flashbot_bundles") {
  def id = column[Long]("id")
  def source = column[String]("source")
  def topic = column[String]("topic")
  def datatype = column[String]("datatype")
  override def * = (id, source, topic, datatype)
}
