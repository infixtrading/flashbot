package com.infixtrading.flashbot.db

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

class Snapshots(tag: Tag) extends Table[SnapshotRow](tag, "flashbot_snapshots") {
  def bundle = column[Long]("bundle")
  def seqid = column[Long]("seqid")
  def micros = column[Long]("micros")
  def data = column[String]("data")
  override def * = (bundle, seqid, micros, data) <> (SnapshotRow.tupled, SnapshotRow.unapply)
  def idx = index("idx_snaps", (bundle, seqid))
}
