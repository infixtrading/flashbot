package flashbot.server

import flashbot.models.core.Portfolio

trait PortfolioRef {
  def mergePortfolio(partial: Portfolio): Unit
  def getPortfolio: Portfolio
}

object PortfolioRef {
  class Isolated(var portfolio: Portfolio) extends PortfolioRef {
    override def mergePortfolio(partial: Portfolio) = {
      portfolio = portfolio.merge(partial)
    }
    override def getPortfolio = portfolio
  }
}
