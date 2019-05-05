package flashbot.core

import flashbot.models.{Market, Portfolio}
import com.quantego.clp

/**
  * PositionManager is a set of functions that analyze a portfolio to answer questions about
  * positions. E.g. What is our current position in asset A? What trades are necessary to achieve
  * a position of X in asset B with the constraints X, Y, and Z?
  */
object PositionManager {

  /**
    * ------------------------------------
    *  Position Percent Group Calculation
    * ------------------------------------
    *
    *   Suppose the following portfolio, which is net long Bitcoin $21k:
    *
    *     portfolio = Map(
    *       Bitmex:XBTUSD -> -1k,      // (XBT, $-1k, +2k PNL, 4x leverage)
    *       Bitmex:ETHUSD -> 0,        // (ETH, $0, 2x leverage)
    *       Bitmex:XBT    -> 2,        // (XBT, $16k)
    *       Binance:BTC   -> 0.5       // (BTC, $4k)
    *     )
    *
    *     Overall BTC position: $4k + ($16k + $2k) - $1k = $21k
    *     Overall ETH position: $0k
    *
    *   We'd like to make trades on Bitmex (but not Binance) so that our overall portfolio is
    *   long Bitcoin and short Ethereum by the same amount in USD and that each of these positions
    *   is only half of what they could be within the same constraints. In this case we'd call:
    *
    *     percentGroup(portfolio,
    *       (Bitmex:XBTUSD, Bitmex:ETHUSD),
    *       (btc -> 0.5, eth -> -0.5)
    *     )
    *
    *   Which would return something along the lines of:
    *
    *     Map(
    *       Bitmex:XBTUSD -> 4k USD,
    *       Bitmex:ETHUSD -> -8k USD
    *     )
    *
    *
    *  1. Isolated buying/selling power per market
    * ----------------------------------------------
    *
    *   Our first step is to determine our buying/selling power isolated per market.
    *
    *     Bitmex:XBT wallet balance: 2 XBT = $16k
    *     Bitmex:XBTUSD PNL = $2k
    *     Bitmex:XBT account equity = $18k
    *
    *     Bitmex:XBTUSD (4x) buying/selling power = $72k
    *     Bitmex:ETHUSD (2x) buying/selling power = $36k
    *
    *
    *  2. Solve for positions
    * -------------------------
    *
    * Bitmex:XBT      <----------------------------------- 0 -- XB ------------------------------->
    *
    * Bitmex:XBTUSD   <----------------------------------- 0 ------- XU -------------------------->
    *
    * Bitmex:ETHUSD   <------------------ EU ------------- 0 ------------------------------------->
    *
    * Binance:BTC     <----------------------------------- 0 ----- 4k ---------------------------->
    *
    * Net:BTC         <----------------------------------- 0 ------------- NB ------------ MaxP -->
    *
    * Net:ETH         <------------------ NE ------------- 0 ----------------------------- MaxP -->
    *
    *
    *   XB = XBT wallet balance USD value
    *   XU = XBTUSD contracts
    *   EU = ETHUSD contracts
    *   MaxP = Max net position per asset in USD (percent 1.0)
    *   NB = Net BTC position in USD
    *   NE = Net ETH position in USD
    *
    *
    *   Where:
    *     XB >= 0
    *     $-72k <= XU <= $72k
    *     $-36k <= EU <= $36k
    *     XU/4 + EU/2 <= $18k
    *
    *     NB = 4k + XU + XB
    *     NE = EU
    *     NB = .5 * MaxP
    *     NE = -.5 * MaxP
    *
    *   First solve for MaxP. Then calculate NB and NE, and solve for the rest of the variables.
    *
    * @param portfolio our whole portfolio
    * @param markets the markets that we'd like to calculate positions for
    * @param targetPercents a map of assets to the desired position percent
    * @param priceMap market prices
    * @param equityDenomination what is buying power equity in terms of? Defaults to USD.
    * @return a map of equity position values per market that satisfy the given percentages.
    */
  def percentGroup(portfolio: Portfolio,
                   markets: Seq[Market],
                   targetPercents: Map[String, Double],
                   priceMap: PriceIndex,
                   equityDenomination: String = "usd"): Map[Market, FixedSize] = {

    val model = new clp.CLP()

    // For all accounts in portfolio, create an account variable.

    // For all positions in portfolio, create a position variable.

    // For all unique assets in portfolio, create a net position variable.

    // Constrain the net assets to each other via ratios.

    // Maximize or minimize the net asset with the largest absolute value ratio.
    // This is our maxP.

    // Solve again, but this time with a hardcoded value for that asset's net position variable.

    // Return the resulting portfolio position values as FixedSize instances of equityDenomination.


//    val model = new Model("Position Solver (Percent Group)")
//    val assets = targetPercents.keys.toSeq
//    val netMaxVar = model.realVar("maxp", 0, 1000000.0, 0.01d)
////    val netMaxVars = assets.map(asset => model.realVar(s"maxp_$asset", 0, 1000000.0, 0.01d))
//
//    val assetBalance = model.realVar("xbt_balance_usd", 0, 500000, .01d)
//    val XU = model.realVar("xbtusd_position", 0, 500000, .01d)
//    val EU = model.realVar("ethusd_position", 0, 500000, .01d)

//    model.post(XU.gt(EU).ibex(.01d))

    ???
  }
}
