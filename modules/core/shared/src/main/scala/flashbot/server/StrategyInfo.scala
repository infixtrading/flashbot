package flashbot.server

/**
  * Description of a strategy.
  *
  * @param jsonSchema the JSON Schema describing params and their constraints.
  */
case class StrategyInfo(jsonSchema: Option[String] = None)
