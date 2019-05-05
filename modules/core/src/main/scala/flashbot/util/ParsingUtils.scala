package flashbot.util

import flashbot.core.InstrumentIndex
import flashbot.models.{Account, Market, Portfolio, Position}

import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers

object ParsingUtils extends RegexParsers {

  val key: Regex = raw"(.+)\.(.+)".r
  val position: Regex = raw"(-?[0-9\.]+)(x[0-9\.]+)?(@[0-9\.]+)?".r

  object Optional {
    def unapply[T](a: T): Option[Option[T]] = if (null == a) Some(None) else Some(Some(a))
  }

  /**
    * Comma separated list of balance or position expressions.
    *
    * bitmex/xbtusd=-10000x2@500,coinbase/btc=5.0,coinbase/usd=0
    * bitmex/xbtusd=-10000@500
    * bitmex/xbtusd=-10000x2,bitmex/xbtusd=-10000
    */
  def parsePortfolio(expr: String)(implicit instruments: InstrumentIndex): Portfolio = {
    expr.split(",").map(_.trim).filterNot(_.isEmpty).foldLeft(Portfolio.empty) {
      case (portfolio, item) => item.split("=").toList match {
        case k@key(exchange, symbol) :: pos :: Nil =>
          (pos, instruments.get(exchange, symbol).isDefined) match {
            case (position(size, Optional(None), Optional(None)), false) =>
              portfolio.withBalance(Account(exchange, symbol), size.toDouble)

            case (position(size, Optional(leverage), Optional(entry)), true) =>
              portfolio.withPosition(Market(exchange, symbol),
                new Position(size.toLong,
                  leverage.map(l => l.slice(1, l.length).toDouble).getOrElse(1.0),
                  entry.map(e => e.slice(1, e.length).toDouble).getOrElse(java.lang.Double.NaN)))

            case _ =>
              throw new RuntimeException(s"No such instrument: $k")
          }
      }
    }
  }
}
