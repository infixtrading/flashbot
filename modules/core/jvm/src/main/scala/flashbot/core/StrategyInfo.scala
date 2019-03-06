package flashbot.core

import flashbot.core.StrategyInfo._
import flashbot.server.GrafanaDashboard.DashboardBuilder
import io.circe.optics.JsonPath
import io.circe.optics.JsonPath._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Json, JsonObject}

/**
  * Description of a strategy.
  *
  * @param jsonSchema the JSON Schema describing params and their constraints.
  * @param layout the layout to use for building the dashboard.
  */
case class StrategyInfo(jsonSchema: Option[Json] = None,
                        layout: DashboardBuilder = Layout.default,
                        title: String = "") {

  def withSchema(schemaStr: String): StrategyInfo = withSchema(parse(schemaStr).right.get)
  def withSchema(newSchema: Json): StrategyInfo = copy(jsonSchema = Some(newSchema))

  def withParamOptions(param: String, options: Seq[Any]): StrategyInfo =
    withParamOptionsOpt(param, options, None)

  def withParamOptions(param: String, options: Seq[Any], default: Any): StrategyInfo =
    withParamOptionsOpt(param, options, Some(default))

  def withParamOptionsOpt(param: String, options: Seq[Any],
                          defaultVal: Option[Any]): StrategyInfo = {
    val enums = options.toVector.map(encodePropertyValue(param, _))
    if (enums.nonEmpty) {
      val withEnums = property(param).as[JsonObject].modify(_.add("enum", enums.asJson))
      val withDefault = property(param).as[JsonObject].modify(o => defaultVal match {
        case Some(v) => o.add("default", encodePropertyValue(param, v))
        case None => o
      })
      withSchema((withEnums compose withDefault) (jsonSchema.get))
    } else this
  }

  def withParamDefault(param: String, value: Any): StrategyInfo = {
    val defaultVal = encodePropertyValue(param, value)
    withSchema(property(param).as[JsonObject].modify(_.add("default", defaultVal))(jsonSchema.get))
  }

  def withLayout(newLayout: DashboardBuilder): StrategyInfo =
    copy(layout = newLayout)

  def updateLayout(updateFn: Layout => Layout): StrategyInfo =
    withLayout(updateFn(layout.asInstanceOf[Layout]))

  private def encodePropertyValue(param: String, value: Any): Json =
    pType(param).getOption(jsonSchema.get) match {
      case Some("string") => value.toString.asJson
      case Some("integer") => encodeValue(value)
      case Some("number") => encodeValue(value)
      case Some("boolean") => encodeValue(value)
    }

  private def encodeValue(value: Any): Json = value match {
    case v: Integer => v.asJson
    case v: java.lang.Integer => v.asJson

    case v: Long => v.asJson
    case v: java.lang.Long => v.asJson

    case v: Double => v.asJson
    case v: java.lang.Double => v.asJson

    case v: Float => v.asJson
    case v: java.lang.Float => v.asJson

    case v: Boolean => v.asJson
    case v: java.lang.Boolean => v.asJson
  }

}

object StrategyInfo {
  private def property(key: String) = root.properties.selectDynamic(key)
  private def pType(key: String) = property(key).`type`.string
  protected[flashbot] def default(key: String): JsonPath = property(key).default
//  private def enum(key: String) = property(key).enum.arr.getOrModify()
}
