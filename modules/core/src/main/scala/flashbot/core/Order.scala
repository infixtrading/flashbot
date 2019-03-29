package flashbot.core
class Order(val id: String,
            val side: Side,
            var amount: Double,
            val price: Option[Double]) {
  def setAmount(newAmount: Double): Unit =
    amount = newAmount

  override def equals(obj: Any) = obj match {
    case Order(_id, _side, _amount, _price) =>
      id == _id && side == _side && amount == _amount && price == _price
    case _ => false
  }

  override def hashCode() = Objects.hash(id, side, amount, price)
}
