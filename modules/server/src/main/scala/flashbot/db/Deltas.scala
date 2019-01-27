package flashbot.db

import slick.lifted.Tag
import slick.jdbc.PostgresProfile.api._

class Deltas(tag: Tag) extends Table[DeltaRow](tag, "flashbot_deltas") {
  def bundle = column[Long]("bundle")
  def seqid = column[Long]("seqid")
  def micros = column[Long]("micros")
  def data = column[String]("data")
  override def * = (bundle, seqid, micros, data) <> (DeltaRow.tupled, DeltaRow.unapply)
  def idx_deltas = index("idx_deltas", (bundle, seqid), unique = true)
  def idx_deltas_micros = index("idx_deltas_micros", micros)
}
