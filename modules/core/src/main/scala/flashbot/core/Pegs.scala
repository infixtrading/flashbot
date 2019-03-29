package flashbot.core

import scala.collection.immutable.Set

class Pegs(pegs: Set[(String, String)]) {

  private lazy val pegMap = pegs
    .flatMap { case (a, b) => Set((a, b), (b, a))}
    .groupBy(_._1)
    .mapValues(_.map(_._2))

  private val empty = Set.empty[String]

  def of(asset: String)(implicit metrics: Metrics): Set[String] =
    pegMap.getOrElse(asset, empty)
}

object Pegs {
  def default: Pegs = new Pegs(Set(
    ("usd", "usdt"),
    ("usd", "usdc"),
    ("usd", "dai"),
    ("usd", "tusd"),
    ("usd", "bitusd"),
    ("btc", "xbt")
  ))
}
