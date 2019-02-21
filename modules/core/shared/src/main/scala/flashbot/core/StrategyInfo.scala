package flashbot.core

import flashbot.server.GrafanaDashboard.DashboardBuilder
import io.circe.Json
import io.circe.parser._

/**
  * Description of a strategy.
  *
  * @param jsonSchema the JSON Schema describing params and their constraints.
  * @param layout the layout to use for building the dashboard.
  */
case class StrategyInfo(jsonSchema: Option[String] = None,
                        layout: DashboardBuilder = Layout.default,
                        title: String = "") {
  def withSchema(schema: String): StrategyInfo =
    copy(jsonSchema = Some(schema))

  def withLayout(newLayout: DashboardBuilder): StrategyInfo =
    copy(layout = newLayout)

  def parseJsonSchema: Json = parse(jsonSchema.get).right.get
}

