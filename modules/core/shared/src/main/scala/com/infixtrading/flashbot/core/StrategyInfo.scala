package com.infixtrading.flashbot.core

import io.circe.Json

/**
  * Description of a strategy.
  *
  * @param schema the JSON Schema describing params and their constraints.
  */
case class StrategyInfo(schema: Json)
