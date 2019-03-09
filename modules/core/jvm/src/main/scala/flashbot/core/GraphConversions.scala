package flashbot.core

import java.util

import flashbot.models.core.{Account, FixedPrice, Market}
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.SimpleDirectedWeightedGraph
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
    *    - For each instrument's quoted pair. (Weight 1)
    *    - For equivalent nodes (Weight 0)
    *      A node is equivalent to another one if their symbols are equal or pegged to each other.
    */
  override def findPricePath(baseKey: AssetKey, quoteKey: AssetKey)
                            (implicit prices: PriceIndex,
                             instruments: InstrumentIndex,
                             metrics: Metrics): Array[FixedPrice[Account]] = {

    val timer = metrics.startTimer("find_price_path")

    val graph = new SimpleDirectedWeightedGraph[AssetKey, Edge[_]](classOf[Edge[_]])

    // Add sink and source symbols
    graph.addVertex(baseKey)
    graph.addVertex(quoteKey)

    // Add account nodes
    val markets = prices.getMarkets
    val filteredInstruments = instruments.filterMarkets(markets.contains)
    val accountNodes = filteredInstruments.assetAccounts.map(acc =>
      acc -> AssetKey(acc.exchange, acc.security)).toMap
    accountNodes.values.foreach { node =>
      graph.addVertex(node)
    }

    val componentNameProvider = new ComponentNameProvider[AssetKey] {
      override def getName(component: AssetKey) = component.toString.replace(""".""", "_")
    }

    val vertexLabelProvider = new ComponentNameProvider[AssetKey] {
      override def getName(component: AssetKey) = component.toString.replace(""".""", "_")
    }

    val edgeLabelProvider = new ComponentNameProvider[Edge[_]] {
      override def getName(component: Edge[_]) = component.toString
    }

    import java.io.StringWriter

    def printGraph() = {
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
    filteredInstruments.byExchange.foreach { case (ex, insts) =>
      insts.foreach { inst =>
        val quoteAccount = Account(ex, inst.quote)
        val baseAccount = Account(ex, inst.base)
        val market = Market(ex, inst.symbol)
        val price = prices.get(market)
        if (!java.lang.Double.isNaN(price)) {
          val b = accountNodes(baseAccount)
          val q = accountNodes(quoteAccount)

          val fp = new FixedPrice(inst.valueDouble(price), (baseAccount, quoteAccount))

          if (graph.addEdge(b, q,
            new PriceEdge(fp))) {
          }

          if (graph.addEdge(q, b,
            new PriceEdge(fp.flip))) {
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
        if (sym == baseKey.security && baseKey.exchangeOpt.isEmpty) {
          val e = new Equiv(new FixedPrice(1, (baseKey.withExchange(node.exchangeOpt.get), node)))
          graph.addEdge(baseKey, node, e)
          graph.setEdgeWeight(baseKey, node, 0)
        }
        if (sym == quoteKey.security && quoteKey.exchangeOpt.isEmpty) {
          val e = new Equiv(new FixedPrice(1, (node, quoteKey.withExchange(node.exchangeOpt.get))))
          graph.addEdge(node, quoteKey, e)
          graph.setEdgeWeight(node, quoteKey, 0)
        }

        // Also link all other accounts for the same symbol
        (accountsBySymbol.getOrElse(sym, Set.empty) - acc).foreach { a =>
          val x = accountNodes(acc)
          val y = accountNodes(a)
          graph.addEdge(x, y, new Equiv(new FixedPrice(1, (x, y))))
          graph.addEdge(y, x, new Equiv(new FixedPrice(1, (y, x))))
          graph.setEdgeWeight(x, y, 0)
          graph.setEdgeWeight(y, x, 0)
        }
      }
    }

    val dijkstras = new DijkstraShortestPath[AssetKey, Edge[_]](graph)

    var edgeSol: Array[FixedPrice[Account]] = null
    try {
      val foo = dijkstras.getPath(baseKey, quoteKey)
      val sol: util.List[Edge[_]] = foo.getEdgeList
      val solScala: Seq[Edge[_]] = sol.asScala
      val priceEdges: Seq[PriceEdge] = solScala.map {
        case _: Equiv => None
        case pe: PriceEdge => Some(pe)
      } collect { case Some(x) => x }
      edgeSol = priceEdges.map(_.fp).toArray
    } catch {
      case npe: NullPointerException =>
//        println(s"Warning: No solution found for $baseKey -> $quoteKey conversion.")
    }

    timer.close()

    edgeSol
  }
}
