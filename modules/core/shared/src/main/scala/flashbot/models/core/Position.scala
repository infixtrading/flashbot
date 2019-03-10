package flashbot.models.core

import flashbot.core.Instrument
import flashbot.core.Instrument.Derivative
import flashbot.core.Num._
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
case class Position(size: Num, leverage: Num, entryPrice: Option[Num]) {
  assert(size.isWholeNumber, "Position size must be a whole (integer) number.")

  /**
    * Updates the position size and average entry price.
    * Returns the new position and any realized PNL that occurred as a side effect.
    */
  def updateSize(newSize: Num, instrument: Derivative, price: Num): (Position, Num) = {

    // First stage, close existing positions if necessary. Record PNL.
    var pnl: Num = `0`
    var tmpSize = size
    if (isShort && newSize > size) {
      tmpSize = newSize min `0`
    } else if (isLong && newSize < size) {
      tmpSize = newSize max `0`
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

  def isLong: Boolean = size > `0`
  def isShort: Boolean = size < `0`

  def initialMargin(instrument: Derivative): Num =
    instrument.value(entryPrice.get) * size.abs / leverage

  override def toString = {
    Seq(Some(size.toString),
        if (leverage == `1`) None else Some("x" + leverage.toString),
        entryPrice.map(p => "@" + p.toString))
      .map(_.getOrElse(""))
      .mkString("")
  }
}
