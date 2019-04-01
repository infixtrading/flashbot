package flashbot.core

import java.util
import flashbot.models.{Account, FixedPrice, Market}
import org.jgrapht.alg.shortestpath.DijkstraShortestPath
import org.jgrapht.graph.SimpleDirectedWeightedGraph
import org.jgrapht.io.{ComponentNameProvider, DOTExporter}

import scala.collection.JavaConverters._
import AssetKey.implicits._
import flashbot.core.AssetKey.SecurityAsset

object GraphConversions extends Conversions {
  abstract class Edge[B: AssetKey, Q: AssetKey] {
    def fp: FixedPrice[B, Q]
  }
  class Equiv[B: AssetKey, Q: AssetKey](val fp: FixedPrice[B, Q]) extends Edge[B, Q] {
    override def toString = "Equiv"
  }
  class PriceEdge(val fp: FixedPrice[Account, Account]) extends Edge[Account, Account] {
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
  override def findPricePath[B, Q](baseKey: B, quoteKey: Q)
                                  (implicit baseOps: AssetKey[B],
                                   quoteOps: AssetKey[Q],
                                   prices: PriceIndex,
                                   instruments: InstrumentIndex,
                                   metrics: Metrics)
      : Array[FixedPrice[Account, Account]] = {

    val timer = metrics.startTimer("find_price_path")

    val graph = new SimpleDirectedWeightedGraph[Any, Edge[_, _]](classOf[Edge[_, _]])

    // Add sink and source symbols
    graph.addVertex(baseKey)
    graph.addVertex(quoteKey)

    // Add account nodes
    val markets = prices.getMarketsJava
    val filteredInstruments = instruments.filterMarkets(markets.contains)
//    val accountNodes = filteredInstruments.assetAccounts
//      .map(acc => acc -> AssetKey(acc)).toMap
    val accounts = filteredInstruments.assetAccounts
    accounts.foreach { node =>
      graph.addVertex(node)
    }

    val componentNameProvider = new ComponentNameProvider[Any] {
      override def getName(component: Any) = component.toString.replace(""".""", "_")
    }

    val vertexLabelProvider = new ComponentNameProvider[Any] {
      override def getName(component: Any) = component.toString.replace(""".""", "_")
    }

    val edgeLabelProvider = new ComponentNameProvider[Edge[_, _]] {
      override def getName(component: Edge[_, _]) = component.toString
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
    val accountsBySymbol = accounts.groupBy(_.security)

    // Add instrument edges
//    println("Instruments:", instruments.byExchange)
//    println("Prices:", prices)
    filteredInstruments.byExchange.foreach { case (ex, insts) =>
      insts.foreach { inst =>
        val quoteAccount = Account(ex, inst.quote)
        val baseAccount = Account(ex, inst.base)
        val market = Market(ex, inst.symbol)
        val price = prices(market)
        if (!price.isNaN) {
//          val b = accountNodes(baseAccount)
//          val q = accountNodes(quoteAccount)
          val b = baseAccount
          val q = quoteAccount

          val fp = new FixedPrice(inst.value(price), (baseAccount, quoteAccount))

          if (graph.addEdge(b, q, new PriceEdge(fp))) {
          }

          if (graph.addEdge(q, b, new PriceEdge(fp.flip))) {
          }
          graph.setEdgeWeight(b, q, 1)
          graph.setEdgeWeight(q, b, 1)
        }
      }
    }

    // Add equivalent edges
    accounts.foreach { case acc @ Account(ex, sec) =>
      // For the security + each of it's pegs.
      (prices.pegs.of(sec) + sec).foreach { sym: String =>
        val securityNode: SecurityAsset = sym
//        val nodeEx: Option[String] = implicitly[AssetKey[SecurityAsset]].exchangeOpt(securityNode)

        // If sym is the sink or source, link it
        if (baseOps.isSymbol && sym == baseOps.security(baseKey)) {
          val e = new Equiv(new FixedPrice(1.0, (baseKey, acc)))
          graph.addEdge(baseKey, acc, e)
          graph.setEdgeWeight(baseKey, acc, 0)
        }

        if (quoteOps.isSymbol && sym == quoteOps.security(quoteKey)) {
          val e = new Equiv(new FixedPrice(1.0, (acc, quoteKey)))
          graph.addEdge(acc, quoteKey, e)
          graph.setEdgeWeight(acc, quoteKey, 0)
        }

        // Also link all other accounts for the same symbol
        (accountsBySymbol.getOrElse(sym, Set.empty) - acc).foreach { a =>
          graph.addEdge(acc, a, new Equiv(new FixedPrice(1.0, (acc, a))))
          graph.addEdge(a, acc, new Equiv(new FixedPrice(1.0, (a, acc))))
          graph.setEdgeWeight(acc, a, 0)
          graph.setEdgeWeight(a, acc, 0)
        }
      }
    }

    val dijkstras = new DijkstraShortestPath[Any, Edge[_, _]](graph)

    var edgeSol: Array[FixedPrice[Account, Account]] = null
    try {
      val foo = dijkstras.getPath(baseKey, quoteKey)
      val sol: util.List[Edge[_, _]] = foo.getEdgeList
      val solScala: Seq[Edge[_, _]] = sol.asScala
      val priceEdges: Seq[PriceEdge] = solScala.map {
        case _: Equiv[_, _] => None
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
