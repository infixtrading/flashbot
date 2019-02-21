package flashbot.server

import io.circe.{Json, JsonObject}
import io.circe.generic.JsonCodec
import io.circe.parser._
import io.circe.syntax._
import json.schema.parser.SimpleType.SimpleType
import json.schema.parser._

object GrafanaDashboard {

  trait DashboardBuilder {
    def buildDashboard(dash: Dashboard): Dashboard
  }

  @JsonCodec
  case class Folder(title: String, id: Option[Long] = None,
                    uid: Option[String] = None, url: Option[String] = None)

  @JsonCodec
  case class Time(from: String, to: String)

  @JsonCodec
  case class Dashboard(id: Option[Long], uid: String, title: String, tags: Seq[String],
                       timezone: String, schemaVersion: Long, version: Long,
                       templating: Option[Templating], panels: Option[Seq[Panel]],
                       time: Option[Time]) {

    def newPanel(panel: Panel): Dashboard = withPanel(panel.copy(id = findNextId))

    def withPanel(panel: Panel): Dashboard =
      copy(panels = Some(panels.getOrElse(Seq.empty)
        .filterNot(_.id == panel.id) :+ panel))

    def clearPanels: Dashboard = copy(panels = Some(Seq()))

    def clearTemplates: Dashboard = copy(templating = Some(Templating(Seq.empty)))

    def mapTemplate(name: String, updater: Template => Template): Dashboard =
      copy(templating = templating.map(_.mapTemplate(name, updater)))

    def withTemplate(template: Template): Dashboard =
      copy(templating = Some(templating.getOrElse(Templating(Seq.empty))
        .withTemplate(template)))

    def withTag(tag: String): Dashboard = copy(tags = (tags :+ tag).distinct)

    def findNextId: Int = {
      var id = 1
      while (panels.getOrElse(Seq.empty).exists(_.id == id)) {
        id = id + 1
      }
      id
    }

    def mapPanels(fn: Panel => Panel): Dashboard = copy(panels = panels.map(_.map(fn)))

    def withJsonSchemaTemplates(schemaStr: String): Dashboard = {
      val schema = JsonSchemaParser.parse(schemaStr).toOption.get
      schema.obj.get.properties.value.foldLeft(this) {
        case (dash, (key, prop)) =>
          dash.withTemplate(mkTemplate(key, label = prop.schema.title)
            .withOptions(prop.schema.enums.map(_.toString).toSeq:_*))
            .mapPanels(_.mapTargets(_.withParam(
              key, prop.schema.types.head.toString, prop.required)))
        }
    }
  }

  @JsonCodec
  case class DashboardPost(dashboard: Dashboard, folderId: Long, overwrite: Boolean)

  @JsonCodec
  case class DashboardPostRsp(id: Long, url: String, status: String, version: Long)

  @JsonCodec
  case class Meta(url: String)

  @JsonCodec
  case class DashboardRsp(dashboard: Dashboard, meta: Meta)

  @JsonCodec
  case class VariableOption(tags: Option[Seq[String]], text: String, value: String,
                            selected: Option[Boolean])

  @JsonCodec
  case class Template(allValue: Option[String], `type`: String, current: Option[VariableOption],
                      hide: Long, includeAll: Boolean, multi: Boolean, name: String,
                      label: Option[String], options: Seq[VariableOption], query: String,
                      refresh: Option[Int], skipUrlSync: Boolean, auto: Option[Boolean],
                      auto_count: Option[Boolean], auto_min: Option[String]) {
    // Add the option and update the query
    def withOption(option: VariableOption): Template =
      copy(options = options :+ option, query = options.map(_.value).mkString(","))

    def withOptions(values: String*): Template =
      values.map(mkOption(_)).foldLeft(this)(_.withOption(_))

    def withLabelledOptions(labelledVals: (String, String)*): Template =
      labelledVals.map(x => mkOption(x._1, Some(x._2))).foldLeft(this)(_.withOption(_))

    def withSelected(valueOpt: Option[String]): Template =
      valueOpt.map(withSelected).getOrElse(this)

    def withSelected(value: String): Template = {
      // Find the option with the given value. It must exist.
      val idx = options.indexWhere(_.value == value)
      val opt = options(idx)

      // Set that option to selected = true, and all others to false
      val newOptions = options.map(_.copy(selected = Some(false)))
        .updated(idx, opt.copy(selected = Some(true)))

      // Also set it as the "current".
      copy(options = newOptions, current = Some(mkCurrent(value, Some(opt.text))))
    }

    def fallbackSelected(value: Option[String]): Template =
      withSelected(selected.orElse(value))

    def selected: Option[String] = current.map(_.value)
  }

  @JsonCodec
  case class Templating(list: Seq[Template]) {
    def withTemplate(tmp: Template): Templating = {
      list.indexWhere(_.name == tmp.name) match {
        case -1 => copy(list = list :+ tmp)
        case i => copy(list = list.updated(i, tmp))
      }
    }

    def mapTemplate(name: String, fn: Template => Template): Templating =
      list.find(_.name == name).map(x => withTemplate(fn(x))).getOrElse(this)
  }

  @JsonCodec
  case class DataSource(id: Option[Long], name: String, `type`: String, access: String,
                        url: String, basicAuth: Boolean, isDefault: Boolean)

  @JsonCodec
  case class DataSourceId(id: Long)

  @JsonCodec
  case class SeriesOverride(alias: String, yaxis: Option[Int] = None,
                            lines: Option[Boolean] = None, bars: Option[Boolean] = None,
                            hideTooltip: Option[Boolean] = None) {
    def left: SeriesOverride = copy(yaxis = Some(1))
    def right: SeriesOverride = copy(yaxis = Some(2))
    def asLines: SeriesOverride = copy(lines = Some(true), bars = Some(false))
    def asBars: SeriesOverride = copy(lines = Some(false), bars = Some(true))
    def noTooltip: SeriesOverride = copy(hideTooltip = Some(true))
  }

  @JsonCodec
  case class Axis(format: String, show: Boolean = true, logBase: Int = 1,
                  label: Option[String] = None, decimals: Option[Int] = None,
                  max: Option[String] = None, min: Option[String] = None)

  @JsonCodec
  case class Legend(show: Boolean = true, avg: Boolean = false, current: Boolean = false,
                    max: Boolean = false, min: Boolean = false, total: Boolean = false,
                    values: Boolean = false)

  @JsonCodec
  case class Panel(id: Long, title: String, `type`: String, gridPos: GridPos,
                   columns: Option[Seq[Json]] = None, datasource: Option[String] = None,
                   fontSize: Option[String] = None, links: Option[Seq[Json]] = None,
                   pageSize: Option[Json] = None, scroll: Option[Boolean] = None,
                   showHeader: Option[Boolean] = None, sort: Option[Sort] = None,
                   styles: Option[Seq[Style]] = None, targets: Option[Seq[Target]] = None,
                   transform: Option[String] = None, seriesOverrides: Option[Seq[SeriesOverride]] = None,
                   yaxes: Option[Seq[Axis]] = None, transparent: Option[Boolean] = None,
                   legend: Option[Legend] = None, collapsed: Option[Boolean] = None,
                   panels: Option[Seq[Panel]] = None) {
    def withTarget(target: Target): Panel = copy(targets = Some(targets.getOrElse(Seq.empty) :+ target))
    def withSeriesOverride(series: SeriesOverride) =
      copy(seriesOverrides = Some(seriesOverrides.getOrElse(Seq.empty)
        .filterNot(_.alias == series.alias) :+ series))
    def withYAxis(axis: Axis): Panel =
      copy(yaxes = Some(yaxes.getOrElse(Seq.empty) :+ axis))
    def setTransparent(value: Boolean): Panel = copy(transparent = Some(value))

    def withLegend(l: Legend): Panel = copy(legend = Some(l))
    def hideLegend: Panel = withLegend(legend.getOrElse(Legend()).copy(show = false))

    def mapTargets(fn: Target => Target): Panel = copy(targets = targets.map(_.map(fn)))
  }

  @JsonCodec
  case class Style(alias: String, dateFormat: Option[String], pattern: String, `type`: String,
                   colors: Option[Seq[String]], decimals: Option[Int], thresholds: Option[Seq[Json]],
                   unit: Option[String])

  @JsonCodec
  case class Target(data: String, hide: Boolean, refId: String, target: String, `type`: String,
                    expr: Option[String], format: Option[String], intervalFactor: Option[Int]) {
    def withField(field: String): Target = withField(field, ("$" + field).asJson)

    def withField(field: String, value: Json): Target = copy(target =
      decode[JsonObject](target).toOption.getOrElse(JsonObject())
        .add(field, value).asJson.noSpaces)

    def withParam(name: String, jsonType: String, required: Boolean) = {
      val obj = decode[JsonObject](target).toOption.getOrElse(JsonObject())
      val params = obj("params").flatMap(_.asObject).getOrElse(JsonObject())
      val newParams = params.add(name, JsonObject(
        "value" -> ("$" + name).asJson,
        "jsonType" -> jsonType.asJson,
        "required" -> required.asJson
      ).asJson)
      copy(target = obj.add("params", newParams.asJson).asJson.noSpaces)
    }
  }

  @JsonCodec
  case class Sort(col: Option[String], desc: Boolean)

  @JsonCodec
  case class GridPos(x: Int, y: Int, w: Int, h: Int)

  val defaultStyle = Style("", None, "/.*/", "number", Some(Seq(
    "rgba(245, 54, 54, 0.9)",
    "rgba(237, 129, 40, 0.89)",
    "rgba(50, 172, 45, 0.97)"
  )), Some(6), Some(Seq.empty), Some("locale"))

  val timeStyle = Style("time", Some("YYYY-MM-DD HH:mm:ss"), "[tT]ime",
    "date", None, None, None, None)

  val defaultStyles = Seq(timeStyle, defaultStyle)

  def mkCurrent(value: String, label: Option[String] = None): VariableOption =
    VariableOption(Some(Seq()), label.getOrElse(value), value, None)

  def mkDashboard(uid: String, title: String,
                  templates: Seq[Template]): Dashboard =
    Dashboard(None, uid, title, Seq("flashbot"), "browser", 16, 0,
      Some(Templating(templates)), Some(Seq.empty), Some(Time("now-24h", "now")))

  def mkTemplate(name: String, `type`: String = "custom", label: Option[String] = None): Template =
    Template(None, `type`, None,
      0, includeAll = false, multi = false, name, label, Seq(),
      "", refresh = Some(2), skipUrlSync = false, None, None, None)

  def mkOption(value: String, text: Option[String] = None, selected: Boolean = false): VariableOption =
    VariableOption(None, text.getOrElse(value), value, Some(selected))

  def mkInterval(name: String = "bar_size"): Template =
    mkTemplate(name, "interval")
    .withOptions("1s", "1m", "5m", "10m", "15m", "30m", "1h", "3h", "6h", "12h", "1d", "7d", "14d", "30d")
    .withSelected("1m")
    .copy(hide = 1)

  def mkTablePanel(id: Long, title: String, gridPos: GridPos): Panel =
    Panel(id, title, "table", gridPos,
      columns = Some(Seq.empty),
      datasource = Some("flashbot"),
      fontSize = Some("100%"),
      links = Some(Seq.empty),
      scroll = Some(true),
      showHeader = Some(true),
      sort = Some(Sort(None, desc = false)),
      styles = Some(defaultStyles),
      targets = Some(Seq.empty),
      transform = Some("table")
    )

  def mkGraphPanel(id: Long, title: String, gridPos: GridPos): Panel =
    Panel(id, title, "graph", gridPos,
      datasource = Some("flashbot"),
      links = Some(Seq.empty),
      targets = Some(Seq.empty),
      seriesOverrides = Some(Seq.empty)
    )

  def mkRowPanel(id: Long, title: String, top: Int): Panel =
    Panel(id, title, "row", GridPos(0, top, 24, 1),
      panels = Some(Seq.empty),
      collapsed = Some(false)
    )

  def mkPricePanel(id: Long, gridPos: GridPos = GridPos(0, 0, 24, 8)): Panel =
    mkGraphPanel(id, "${market} Price", gridPos)
      .withTarget(mkGraphTarget("price")
        .withField("market")
        .withField("bar_size"))
    .withSeriesOverride(SeriesOverride("price").right)
    .withSeriesOverride(SeriesOverride("volume").left.asBars.noTooltip)
    .withYAxis(Axis("locale", show = false, max = Some("4"), min = Some("0")))
    .withYAxis(Axis("locale"))
    .setTransparent(true)
    .hideLegend

  def mkTableTarget(key: String): Target = Target(
    "", hide = false, "A", Json.obj("key" -> key.asJson, "type" -> "table".asJson).noSpaces,
    "table", Some(""), Some("table"), Some(1))

  def mkGraphTarget(key: String): Target = Target(
    "", hide = false, "A", Json.obj("key" -> key.asJson, "type" -> "time_series".asJson).noSpaces,
    "timeseries", None, None, None)
}
