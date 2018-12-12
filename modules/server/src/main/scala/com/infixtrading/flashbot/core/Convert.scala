package com.infixtrading.flashbot.core

import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.SimpleWeightedGraph
import scala.collection.JavaConverters._

object Convert {

  class Node(val symbol: String, val exchange: Option[String] = None)

  /**
    * Find base/quote price using all prices defined in the index.
    *
    * Search algorithm: Dijkstras shortest path search.
    *
    * 1. Create a node for each account defined by our instruments.
    *    Also a dedicated node for the current security (source) and the target (sink).
    *
    * 2. Populate edges:
    *    - For each instrument's tradable pair. (Weight 1)
    *    - For equivalent nodes (Weight 0)
    *      A node is equivalent to another one if their symbols are equal or pegged to each other.
    */
  def findPrice(base: String, quote: String)
               (implicit prices: PriceIndex, instruments: InstrumentIndex): Option[Double] = {
    val graph = new SimpleWeightedGraph[Node, Int](classOf[Int])

    val source = new Node(base)
    val sink = new Node(quote)

    // Add sink and source
    graph.addVertex(source)
    graph.addVertex(sink)

    // Add account nodes
    val accountNodes = instruments.accounts.map(acc =>
      acc -> new Node(acc.security, Some(acc.exchange))).toMap
    accountNodes.values.foreach { node =>
      graph.addVertex(node)
    }

    // Index accounts by security
    val accountIndex = accountNodes.keySet.groupBy(_.security)

    // Keep track of securities and exchanges
    val exchanges = accountNodes.keySet.map(_.exchange)
    val securities = accountNodes.keySet.map(_.security)

    // Add instrument edges
    instruments.instruments.foreach { case (ex, insts) =>
      insts.foreach { inst =>
        graph.addEdge(
          accountNodes(Account(ex, inst.settledIn)),
          accountNodes(Account(ex, inst.security.get)), 1)
      }
    }

    // Add equivalent edges
    accountNodes.foreach { case (acc @ Account(ex, sec), node) =>
      // For the security + each of it's pegs.
      (prices.pegs.of(sec) + sec).foreach { sym =>
        // If sym is the sink or source, link it
        if (sym == base) graph.addEdge(source, node, 0)
        if (sym == quote) graph.addEdge(sink, node, 0)

        // Also link all other accounts for the same symbol
        (accountIndex.getOrElse(sym, Set.empty) - acc).foreach { a =>
          graph.addEdge(accountNodes(acc), accountNodes(a), 0)
        }
      }
    }

    val dijkstras = new DijkstraShortestPath[Node, Int](graph)
    val solution = dijkstras.getPath(source, sink).getVertexList.asScala

    // Iterate over the nodes in the solution to compute the final base/quote price.
    var lastNode: Option[Node] = None
    var price: Option[Double] = Some(1)
    solution.slice(1, solution.size - 1).foreach { node =>
      if (lastNode.isDefined) {
        if (lastNode.get.exchange.get == node.exchange.get) {
          // First find a forward price.
          val forwardPrice: Option[Double] = instruments.instruments(node.exchange.get)
            .find(i => i.security.get == lastNode.get.symbol && i.settledIn == node.symbol)
            .map(i => prices(Market(node.exchange.get, i.symbol)))

          // Otherwise look for backward price.
          val finalPrice = forwardPrice.orElse(instruments.instruments(node.exchange.get)
            .find(i => i.security.get == node.symbol && i.settledIn == lastNode.get.symbol)
            .map(i => 1d / prices(Market(node.exchange.get, i.symbol))))

          if (finalPrice.isDefined) {
            price = price.map(_ * finalPrice.get)
          }
        }
      } else {
        price = Some(1)
      }
      lastNode = Some(node)
    }

    price
  }

  implicit class ConvertOps(size: FixedSize) {

    /**
      * Convert using only the prices on this exchange, unless looseConversion is true. In that
      * case, we fallback to other exchanges in case we can't find a conversion on this one.
      */
    def as(targetSecurity: String, exchange: String, looseConversion: Boolean = false)
          (implicit prices: PriceIndex, instruments: InstrumentIndex): Option[FixedSize] =
      as(targetSecurity)(prices.forExchange(exchange), instruments)
        .orElse(if (looseConversion) as(targetSecurity) else None)

    /**
      * Convert using prices from all exchanges.
      */
    def as(targetSecurity: String)
          (implicit prices: PriceIndex, instruments: InstrumentIndex): Option[FixedSize] = {
      // Expand this security to all of it's pegs.
      //      val eqAssets = prices.pegs.of(security) + security
      throw new NotImplementedError()
    }
  }
}
