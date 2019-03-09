package flashbot.models.core

import flashbot.core.Instrument
import flashbot.core.Instrument.Derivative
import io.circe.generic.JsonCodec

/**
  * Positions are used to calculate the portfolio equity and PnL.
  *
  * @param size positive (long) or negative (short) position in some security.
  * @param leverage the leverage of the position. 1 for no leverage used.
  * @param entryPrice this will be empty for uninitialized positions, which are positions
  *                   that may be used for the initial portfolio in backtests, where we
  *                   don't know the entry price at the time of portfolio creation.
  */
@JsonCodec
case class Position(size: Long, leverage: Double, entryPrice: Option[Double]) {

  /**
    * Updates the position size and average entry price.
    * Returns the new position and any realized PNL that occurred as a side effect.
    */
  def setSize(newSize: Long, instrument: Derivative, price: Double): (Position, Double) = {

    // First stage, close existing positions if necessary. Record PNL.
    var pnl: Double = 0
    var tmpSize = size
    if (isShort && newSize > size) {
      tmpSize = math.min(newSize, 0)
    } else if (isLong && newSize < size) {
      tmpSize = math.max(newSize, 0)
    }

    if (tmpSize != size) {
      pnl = instrument.pnl(size - tmpSize, entryPrice.get, price)
    }

    // Second stage, increase to new size and update entryPrice.
    val enterSize = math.abs(newSize - tmpSize)
    val newEntryPrice: Double =
      (tmpSize * entryPrice.get + enterSize * price) / (tmpSize + enterSize)

    (copy(
      size = newSize,
      entryPrice = Some(newEntryPrice)
    ), pnl)
  }

  def isLong: Boolean = size > 0
  def isShort: Boolean = size < 0

  def initialMargin(instrument: Derivative): Double =
    instrument.valueDouble(entryPrice.get) * math.abs(size) / leverage

  override def toString = {
    Seq(Some(size.toString),
        if (leverage == 1) None else Some("x" + leverage),
        entryPrice.map(p => "@" + p))
      .map(_.getOrElse(""))
      .mkString("")
  }
}
