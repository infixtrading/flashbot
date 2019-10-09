package flashbot.core

import flashbot.models.Portfolio
import flashbot.util.ParsingUtils

trait PortfolioRef {
  def getPortfolio(instruments: Option[InstrumentIndex]): Portfolio
  def printPortfolio: String

  def acquirePortfolio(ctx: TradingSession): Portfolio

  def releasePortfolio(portfolio: Portfolio): Unit

  def update(ctx: TradingSession, fn: Portfolio => Portfolio): Unit = {

    val p = acquirePortfolio(ctx)

    // Record the update.
    p.record(fn)

    // Grab the lastUpdate event, which will be a BatchPortfolioUpdate.
    val batchEvent = p.lastUpdate.get

    // Release it
    releasePortfolio(p)

    // Emit event to the session.
    ctx.emitReportEvent(batchEvent)
  }
}

object PortfolioRef {
  class Isolated(initialPortfolioStr: String) extends PortfolioRef {

    var portfolio: Option[Portfolio] = None

    override def getPortfolio(instruments: Option[InstrumentIndex]): Portfolio = {
      if (portfolio.isEmpty)
        portfolio = Some(ParsingUtils.parsePortfolio(initialPortfolioStr)(instruments.get))
      portfolio.get
    }

    override def printPortfolio: String =
      if (portfolio.isEmpty) initialPortfolioStr
      else portfolio.toString

    override def acquirePortfolio(ctx: TradingSession): Portfolio = {
      // Simply get the portfolio. No locking required.
      getPortfolio(Some(ctx.instruments))
    }

    override def releasePortfolio(portfolio: Portfolio): Unit = {
      // No-op
    }
  }
}
