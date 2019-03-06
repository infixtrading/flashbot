package flashbot.server

import flashbot.core.InstrumentIndex
import flashbot.models.core.Portfolio
import flashbot.util.ParsingUtils

trait PortfolioRef {
  def mergePortfolio(partial: Portfolio): Unit
  def getPortfolio(instruments: Option[InstrumentIndex]): Portfolio
  def printPortfolio: String
}

object PortfolioRef {
  class Isolated(initialPortfolioStr: String) extends PortfolioRef {

    var portfolio: Option[Portfolio] = None

    override def mergePortfolio(partial: Portfolio) = {
      portfolio = Some(portfolio.get.merge(partial))
    }

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
