package com.infixtrading.flashbot.db

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

class Deltas(tag: Tag) extends Table[DeltaRow](tag, "flashbot_deltas") {
  def bundle = column[Long]("bundle")
  def seqid = column[Long]("seqid")
  def micros = column[Long]("micros")
  def data = column[String]("data")
  override def * = (bundle, seqid, micros, data) <> (DeltaRow.tupled, DeltaRow.unapply)
  def idx = index("idx_seq", (bundle, seqid))
}
