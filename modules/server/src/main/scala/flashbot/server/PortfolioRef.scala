package flashbot.server

import flashbot.core.{InstrumentIndex, TradingSession}
import flashbot.models.core.{BatchPortfolioUpdate, Portfolio, PortfolioDelta}
import flashbot.util.ParsingUtils

trait PortfolioRef {
  def getPortfolio(instruments: Option[InstrumentIndex]): Portfolio
  def printPortfolio: String

  // No locking by default. The isolated portfolio uses this default implementation.
  def update(ctx: TradingSession, fn: Portfolio => Portfolio) = {
    // Record the update.
    getPortfolio(None).record(fn)
    // Grab the lastUpdate event, which will be a BatchPortfolioUpdate.
    val batchEvent = getPortfolio(None).lastUpdate.get
    // Emit it to the session.
    ctx.send(batchEvent)
  }
}

object PortfolioRef {
  class Isolated(initialPortfolioStr: String) extends PortfolioRef {

    var portfolio: Option[Portfolio] = None

    override def getPortfolio(instruments: Option[InstrumentIndex]) = {
      if (portfolio.isEmpty)
        portfolio = Some(ParsingUtils.parsePortfolio(initialPortfolioStr)(instruments.get))
      portfolio.get
    }

    override def printPortfolio =
      if (portfolio.isEmpty) initialPortfolioStr
      else portfolio.toString

  }
}
