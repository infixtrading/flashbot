package flashbot.core

import flashbot.models.core.FixedSize

object Transaction {
  implicit def ordering: Ordering[Transaction] = new Ordering[Transaction] {
    override def compare(x: Transaction, y: Transaction) = x.micros.compareTo(y.micros)
  }
}

sealed trait Transaction {
  def id: String
  def micros: Long
}

case class TradeTx(trade: Trade) extends Transaction {
  def id = trade.id
  def micros = trade.micros
}

case class Deposit(id: String, micros: Long, size: FixedSize[Double]) extends Transaction

case class Withdraw(id: String, micros: Long, size: FixedSize[Double]) extends Transaction
