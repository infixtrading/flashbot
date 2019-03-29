package flashbot.core

import flashbot.core.Layout._
import flashbot.server.GrafanaDashboard
import flashbot.server.GrafanaDashboard._

case class Layout(queries: Seq[Query] = Seq.empty,
                  panelConfigs: Map[String, PanelConfig] = Map.empty,
                  rowConfigs: Map[String, RowConfig] = Map.empty) extends DashboardBuilder {

  def addPanel(title: String, row: String): Layout = addPanel(title, Some(row))
  def addPanel(title: String): Layout = addPanel(title, None)

  def addPanel(name: String, row: Option[String]): Layout = {
    if (panelConfigs.isDefinedAt(name)) {
      throw new RuntimeException(s"Panel $name already exists.")
    }
    copy(panelConfigs = panelConfigs + (name -> PanelConfig(row = row)))
  }

  def addTimeSeries(key: String, panel: String): Layout = addTimeSeries(key, panel, q => q)
  def addTimeSeries(key: String, panel: String, fn: Query => Query): Layout =
    addQuery(fn(Query(key, panel, TimeSeriesType)))

  def addTable(key: String, title: String, row: String): Layout = addTable(key, title, row, q => q)
  def addTable(key: String, title: String, row: String, fn: Query => Query): Layout =
    addPanel(title, row).addQuery(fn(Query(key, title, TableType)))

  def addQuery(query: Query): Layout = {
    if (findQuery(query.key, query.panel).isDefined) {
      throw new RuntimeException(s"Query ${query.key} already exists in panel ${query.panel}")
    }
    copy(queries = queries :+ query)
  }

  def updateQuery(key: String, panel: String, fn: Query => Query) = {
    val q = findQuery(key, panel)
    var layout = removeQuery(key, panel)
    val newQuery = fn(q.get)
    addQuery(newQuery)
  }

  def removeQuery(key: String, panel: String): Layout = {
    val newQueries = queries.filterNot(q => q.key == key && q.panel == panel)
    if (newQueries.size == queries.size) {
      throw new RuntimeException(s"No query with key: $key and panel: $panel")
    }
    copy(queries = newQueries)
  }

  def findQuery(key: String, panel: String): Option[Query] =
    queries.find(q => q.key == key && q.panel == panel)

  def configurePanel(title: String, updater: PanelConfig => PanelConfig) = ???
  def configurePanel(title: String, row: String, updater: PanelConfig => PanelConfig) = ???
  def configureRow(title: String, updater: RowConfig => RowConfig) = ???

  def findPanels: Seq[String] = queries.map(_.panel).distinct
  def findRows: Seq[String] = findPanels.map(p => panelConfigs(p).row)
    .collect { case Some(x) => x }.distinct

  def panelsForRow(row: Option[String]): Seq[String] =
    panelConfigs.filter(_._2.row == row).keys.toSeq.distinct

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
        gPanel = Some(GrafanaDashboard
          .mkGraphPanel(0, panel, GridPos(x, y, w, h))
          .withYAxis(Axis("locale", decimals = Some(6)))
          .withYAxis(Axis("locale", decimals = Some(6)))
        )
        queries.foreach(q => gPanel = for {
          panel <- gPanel
          withTarget = panel.withTarget(GrafanaDashboard.mkGraphTarget(q.key))
          withFill = q.fill.map(f =>
            withTarget.withSeriesOverride(q.key, _.fill(f))).getOrElse(withTarget)
          withWidth = q.lineWidth.map(w =>
            withFill.withSeriesOverride(q.key, _.width(w))).getOrElse(withFill)
          withColor = q.color.map(c =>
            withWidth.withSeriesOverride(q.key, _.color(c))).getOrElse(withWidth)
          withAxis = q.axis.map(a =>
            withColor.withSeriesOverride(q.key, _.axis(a))).getOrElse(withColor)
        } yield withAxis)
      } else {
        throw new RuntimeException("Unable to build dashboard. " +
          "Table and time series queries cannot be mixed.")
      }
      dashboard = dashboard.newPanel(gPanel.map(_.withLegend(Legend())).get)
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
    findRows.foreach(row => buildRow(Some(row)))

    dashboard
  }
}

object Layout {

  case class Query(key: String, panel: String, `type`: QueryType,
                   isPrimary: Boolean = true,
                   fill: Option[Boolean] = None,
                   lineWidth: Option[Int] = None,
                   color: Option[String] = None,
                   axis: Option[Int] = None) {
    def setPrimary(value: Boolean): Query = copy(isPrimary = value)
    def setType(ty: QueryType): Query = copy(`type` = ty)
    def setFill(v: Boolean): Query = copy(fill = Some(v))
    def setLineWidth(w: Int): Query = copy(lineWidth = Some(w))
    def setColor(color: String): Query = copy(color = Some(color))
    def setAxis(axis: Int): Query = copy(axis = Some(axis))
  }

  case class PanelConfig(height: Option[Int] = None, row: Option[String])

  case class RowConfig(maxCols: Option[Int] = None) {
    assert(maxCols.forall(x => x > 0 && x < 5))
  }

  val default = Layout()
    .addPanel("Equity", "Portfolio")
    .addTimeSeries("equity", "Equity")
    .addTimeSeries("buy_and_hold", "Equity")

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

