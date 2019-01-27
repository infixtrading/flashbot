package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.Instrument
import com.infixtrading.flashbot.models.core.Order.Side

sealed trait ExchangeRequest

sealed trait OrderRequest extends ExchangeRequest {
  val clientOid: String
  val side: Side
  val product: Instrument
}

case class LimitOrderRequest(clientOid: String,
                             side: Side,
                             product: Instrument,
                             size: Double,
                             price: Double,
                             postOnly: Boolean) extends OrderRequest

case class MarketOrderRequest(clientOid: String,
                              side: Side,
                              product: Instrument,
                              size: Option[Double],
                              funds: Option[Double]) extends OrderRequest

