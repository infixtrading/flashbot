package flashbot.models

import io.circe.generic.JsonCodec
import scala.collection.mutable

@JsonCodec
sealed trait PortfolioDelta

@JsonCodec
case class BalanceUpdated(account: Account, balance: Option[Double]) extends PortfolioDelta

@JsonCodec
case class PositionUpdated(market: Market, position: Option[Position]) extends PortfolioDelta

@JsonCodec
case class OrdersUpdated(market: Market, bookDelta: OrderBook.Delta) extends PortfolioDelta

@JsonCodec
case class BatchPortfolioUpdate(deltas: mutable.Buffer[PortfolioDelta]) extends PortfolioDelta

