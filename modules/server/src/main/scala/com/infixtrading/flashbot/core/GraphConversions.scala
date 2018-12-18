package com.infixtrading.flashbot.core

import java.util

import com.infixtrading.flashbot.models.core._
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.{SimpleDirectedWeightedGraph, SimpleWeightedGraph}
import org.jgrapht.io.{ComponentNameProvider, DOTExporter}

import scala.collection.JavaConverters._

object GraphConversions extends Conversions {
  abstract class Edge[T <: HasSecurity] {
    def fp: FixedPrice[T]
  }
  class Equiv(val fp: FixedPrice[AssetKey]) extends Edge[AssetKey] {
    override def toString = "Equiv"
  }
  class PriceEdge(val fp: FixedPrice[Account]) extends Edge[Account] {
    def flip = new PriceEdge(fp.flip)
    override def toString = fp.toString
  }

  /**
    * Find a base to quote price path using all markets defined in the index.
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
  override def findPricePath(baseKey: AssetKey, quoteKey: AssetKey)
                            (implicit prices: PriceIndex,
                             instruments: InstrumentIndex): Option[Seq[FixedPrice[Account]]] = {

    val graph = new SimpleDirectedWeightedGraph[AssetKey, Edge[_]](classOf[Edge[_]])

    // Add sink and source symbols
    graph.addVertex(baseKey)
    graph.addVertex(quoteKey)

    // Add account nodes
    val accountNodes = instruments.accounts.map(acc =>
      acc -> AssetKey(acc.exchange, acc.security)).toMap
    accountNodes.values.foreach { node =>
      graph.addVertex(node)
    }

    val componentNameProvider = new ComponentNameProvider[AssetKey] {
      override def getName(component: AssetKey) = component.toString.replace("""/""", "_")
    }

    val vertexLabelProvider = new ComponentNameProvider[AssetKey] {
      override def getName(component: AssetKey) = component.toString.replace("""/""", "_")
    }

    val edgeLabelProvider = new ComponentNameProvider[Edge[_]] {
      override def getName(component: Edge[_]) = component.toString
    }

    import java.io.StringWriter
    import java.io.Writer

    def printGraph = {
     val writer = new StringWriter

      new DOTExporter(componentNameProvider, vertexLabelProvider, edgeLabelProvider)
        .exportGraph(graph, writer)

      println(baseKey, quoteKey)
      println(writer.toString)

    }


    // Index accounts by security
    val accountsBySymbol = accountNodes.keySet.groupBy(_.security)

    // Add instrument edges
//    println("Instruments:", instruments.byExchange)
//    println("Prices:", prices)
    instruments.byExchange.foreach { case (ex, insts) =>
      insts.foreach { inst =>
        // Add settlement prices. E.g. (bitmex/xbtusd) <-> (bitmex/xbt)
        val settlementAccount = Account(ex, inst.settledIn)
        val securityAccount = Account(ex, inst.security.get)
        val market = Market(ex, inst.symbol)
        if (prices.get(market).isDefined) {
          val b = accountNodes(securityAccount)
          val q = accountNodes(settlementAccount)
          val forwardEdge = new PriceEdge(
            FixedPrice(prices(market), (securityAccount, settlementAccount)))
          if (graph.addEdge(b, q, forwardEdge)) {
//            printGraph
          }
          if (graph.addEdge(q, b, forwardEdge.flip)) {
//            printGraph
          }
          graph.setEdgeWeight(b, q, 1)
          graph.setEdgeWeight(q, b, 1)
        }
      }
    }

    // Add equivalent edges
    accountNodes.foreach { case (acc @ Account(ex, sec), node) =>
      // For the security + each of it's pegs.
      (prices.pegs.of(sec) + sec).foreach { sym =>
        // If sym is the sink or source, link it
        if (sym == baseKey.security && baseKey.exchange.isEmpty) {
          val e = new Equiv(FixedPrice(1, (baseKey.withExchange(node.exchange.get), node)))
          graph.addEdge(baseKey, node, e)
          graph.setEdgeWeight(baseKey, node, 0)
        }
        if (sym == quoteKey.security && quoteKey.exchange.isEmpty) {
          val e = new Equiv(FixedPrice(1, (node, quoteKey.withExchange(node.exchange.get))))
          graph.addEdge(node, quoteKey, e)
          graph.setEdgeWeight(node, quoteKey, 0)
        }

        // Also link all other accounts for the same symbol
        (accountsBySymbol.getOrElse(sym, Set.empty) - acc).foreach { a =>
          val x = accountNodes(acc)
          val y = accountNodes(a)
          graph.addEdge(x, y, new Equiv(FixedPrice(1, (x, y))))
          graph.addEdge(y, x, new Equiv(FixedPrice(1, (y, x))))
          graph.setEdgeWeight(x, y, 0)
          graph.setEdgeWeight(y, x, 0)
        }
      }
    }

    val dijkstras = new DijkstraShortestPath[AssetKey, Edge[_]](graph)

    var edgeSol: Option[Seq[FixedPrice[Account]]] = None
    try {
      val foo = dijkstras.getPath(baseKey, quoteKey)
      val sol: util.List[Edge[_]] = foo.getEdgeList
      val solScala: Seq[Edge[_]] = sol.asScala.toVector
      val priceEdges: Seq[PriceEdge] = solScala.map {
        case _: Equiv => None
        case pe: PriceEdge => Some(pe)
      } collect { case Some(x) => x }
      edgeSol = Option(priceEdges.map(_.fp))
    } catch {
      case npe: NullPointerException =>
        println(s"Warning: No solution found for $baseKey -> $quoteKey conversion.")
    }

    edgeSol
  }
}
