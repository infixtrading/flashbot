package com.infixtrading.flashbot.core
import io.circe._
import io.circe.generic.semiauto._

case class Position(size: Long, leverage: Double, entryPrice: Double) {

  /**
    * Updates the position size and average entry price.
    * Returns the new position and any realized PNL that occurred as a side effect.
    */
  def setSize(newSize: Long, instrument: Instrument, price: Double): (Position, Double) = {

    // First stage, close existing positions if necessary. Record PNL.
    var pnl: Double = 0
    var tmpSize = size
    if (isShort && newSize > size) {
      tmpSize = math.min(newSize, 0)
    } else if (isLong && newSize < size) {
      tmpSize = math.max(newSize, 0)
    }

    if (tmpSize != size) {
      pnl = instrument.PNL(size - tmpSize, entryPrice, price)
    }

    // Second stage, increase to new size and update entryPrice.
    val enterSize = math.abs(newSize - tmpSize)
    val newEntryPrice: Double =
      (tmpSize * entryPrice + enterSize * price) / (tmpSize + enterSize)

    (copy(
      size = newSize,
      entryPrice = newEntryPrice
    ), pnl)
  }

  def isLong: Boolean = size > 0
  def isShort: Boolean = size < 0
}

object Position {
  implicit val postionEn: Encoder[Position] = deriveEncoder
  implicit val postionDe: Decoder[Position] = deriveDecoder
}

