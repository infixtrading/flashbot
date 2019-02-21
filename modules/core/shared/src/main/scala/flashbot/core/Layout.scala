package flashbot.core

import flashbot.core.Layout._
import flashbot.server.GrafanaDashboard
import flashbot.server.GrafanaDashboard.{Dashboard, DashboardBuilder, GridPos}

case class Layout(queries: Seq[Query] = Seq.empty,
                  panelConfigs: Map[String, PanelConfig] = Map.empty,
                  rowConfigs: Map[String, RowConfig] = Map.empty) extends DashboardBuilder {

  def addTimeSeries(key: String, panel: String): Layout = ???
  def addTimeSeries(key: String, panel: String, row: String): Layout = ???
  def addTimeSeries(key: String, panel: String, fn: Query => Query): Layout = ???
  def addTimeSeries(key: String, panel: String, row: String, fn: Query => Query): Layout = ???

  def addTable(key: String, title: String): Layout = ???
  def addTable(key: String, title: String, row: String): Layout = ???
  def addTable(key: String, title: String, fn: Query => Query): Layout = ???
  def addTable(key: String, title: String, row: String, fn: Query => Query): Layout = ???

  def addQuery(key: String, panel: String, fn: Query => Query): Layout = ???
  def updateQuery(key: String, panel: String, fn: Query => Query) = ???

  def configurePanel(title: String, updater: PanelConfig => PanelConfig) = ???
  def configurePanel(title: String, row: String, updater: PanelConfig => PanelConfig) = ???
  def configureRow(title: String, updater: RowConfig => RowConfig) = ???

  def rows: Seq[String] = queries.map(_.row).collect { case Some(x) => x }.distinct

  def panelsForRow(row: Option[String]): Seq[String] =
    queries.filter(_.row == row).map(_.panel).distinct

  def queriesForPanel(panel: String): Seq[Query] =
    queries.filter(_.panel == panel)

  def rowConfig(row: Option[String]): Option[RowConfig] =
    row.flatMap(r => rowConfigs.get(r))

  override def buildDashboard(dash: Dashboard) = {
    var dashboard = dash.clearPanels
    var top = 0

    def buildPanel(panel: String, x: Int, y: Int, w: Int, h: Int): Unit = {
      val queries = queriesForPanel(panel)
      var gPanel: Option[GrafanaDashboard.Panel] = None
      if (queries.size == 1 && queries.head.`type` == TableType) {
        gPanel = Some(
          GrafanaDashboard.mkTablePanel(0, panel, GridPos(x, y, w, h))
            .withTarget(GrafanaDashboard.mkTableTarget(queries.head.key)))
      } else if (queries.forall(_.`type` == TimeSeriesType)) {
        gPanel = Some(GrafanaDashboard.mkGraphPanel(0, panel, GridPos(x, y, w, h)))
        queries.foreach(q => {
          gPanel = gPanel.map(_.withTarget(GrafanaDashboard.mkGraphTarget(q.key)))
        })
      } else {
        throw new RuntimeException("Unable to build dashboard. " +
          "Table and time series queries cannot be mixed.")
      }
      dashboard = dashboard.newPanel(gPanel.get)
    }

    def buildRow(row: Option[String]): Unit = {
      if (row.isDefined) {
        dashboard = dashboard.newPanel(GrafanaDashboard.mkRowPanel(0, row.get, top))
        top += 1
      }

      val config = rowConfig(row)
      var maxHeight = 0
      panelsForRow(row).grouped(config.flatMap(_.maxCols).getOrElse(1))
        .foreach(panels => panels.zipWithIndex.foreach(pi => {
          val panelHeight = panelConfigs.get(pi._1).flatMap(_.height).getOrElse(8)
          maxHeight = math.max(panelHeight, maxHeight)
          buildPanel(pi._1, pi._2 * 24 / panels.size, top, 24 / panels.size, panelHeight)
        }))
      top += maxHeight
    }

    // Create panels that are not assigned to a row.
    buildRow(None)

    // Iterate through rows, add each row as a panel and then add the corresponding
    // panels. Keep track of the current y coordinate. Rows have a height of 1.
    rows.foreach(row => buildRow(Some(row)))

    dashboard
  }
}

object Layout {

  case class Query(key: String, panel: String, `type`: QueryType,
                   row: Option[String] = None, isPrimary: Boolean = true) {
    def setPrimary(value: Boolean): Query = copy(isPrimary = value)
  }

  case class PanelConfig(height: Option[Int] = None)

  case class RowConfig(maxCols: Option[Int] = None) {
    assert(maxCols.forall(x => x > 0 && x < 5))
  }

  val default = Layout().addTimeSeries("equity.usd", "Equity", "Portfolio")

//    .addQuery("price", "$market Price", "Prices")
//    .addQuery("volume", "$market Price", "Prices", _.setPrimary(false))

//  val default: Layout = Layout(
//    rows = Seq(
//      Row("Portfolio", Seq(
//        Panel("Equity", Seq(Query("equity.usd")))
//      ))
//    )
//  )

  private def insertAt[T](seq: Seq[T], item: T, index: Int): Seq[T] = {
    val i = if (index >= 0) index else seq.size
    val (a, b) = seq.splitAt(i)
    (a :+ item) ++ b
  }

  sealed trait QueryType
  case object TimeSeriesType extends QueryType
  case object TableType extends QueryType
}

