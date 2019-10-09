package flashbot.db

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

class Deltas(tag: Tag) extends Table[DeltaRow](tag, "flashbot_deltas") {
  def id = column[Long]("id", O.AutoInc)
  def bundle = column[Long]("bundle")
  def seqid = column[Long]("seqid")
  def micros = column[Long]("micros")
  def data = column[String]("data")
  def backfill = column[Option[Long]]("backfill")
  override def * = (id, bundle, seqid, micros, data, backfill) <> (DeltaRow.tupled, DeltaRow.unapply)
  def idx_deltas_id = index("idx_deltas_id", id)
  def idx_deltas = index("idx_deltas", (bundle, seqid), unique = true)
  def idx_deltas_micros = index("idx_deltas_micros", micros)
}
