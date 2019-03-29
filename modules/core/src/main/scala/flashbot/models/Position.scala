package flashbot.models

import flashbot.core.Instrument
import flashbot.core.Instrument.Derivative
import io.circe.generic.JsonCodec

/**
  * Positions are used to calculate the portfolio equity and PnL.
  *
  * @param size positive (long) or negative (short) position in some security.
  * @param leverage the leverage of the position. 1 for no leverage used.
  * @param entryPrice this will be NaN for uninitialized positions, which are positions
  *                   that may be used for the initial portfolio in backtests, where we
  *                   don't know the entry price at the time of portfolio creation.
  */
class Position(var size: Double, var leverage: Double, var entryPrice: Double) {

  /**
    * Updates the position size and average entry price.
    * Returns the new position and any realized PNL that occurred as a side effect.
    */
  def updateSize(newSize: Double, instrument: Derivative, price: Double): (Position, Double) = {

    // First stage, close existing positions if necessary. Record PNL.
    var pnl = 0d
    var tmpSize = size
    if (isShort && newSize > size) {
      tmpSize = newSize min 0
    } else if (isLong && newSize < size) {
      tmpSize = newSize max 0
    }

    if (tmpSize != size) {
      pnl = instrument.pnl(size - tmpSize, entryPrice.get, price)
    }

    // Second stage, increase to new size and update entryPrice.
    val enterSize = (newSize - tmpSize).abs
    val newEntryPrice =
      (tmpSize * entryPrice.get + enterSize * price) / (tmpSize + enterSize)

    (copy(
      size = newSize,
      entryPrice = Some(newEntryPrice)
    ), pnl)
  }

  def isLong: Boolean = size > 0
  def isShort: Boolean = size < 0

  def initialMargin(instrument: Derivative): Double =
    instrument.value(entryPrice) * size.abs / leverage

  override def toString = Seq(
    size.toString,
    if (leverage == 1) "" else "x" + leverage,
    "@" + entryPrice
  ).mkString("")
}
