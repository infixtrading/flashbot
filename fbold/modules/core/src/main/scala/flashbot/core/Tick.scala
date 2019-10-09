package flashbot.core

import flashbot.models.ExchangeRequest

trait Tick

case class Callback(fn: Runnable) extends Tick
case class SimulatedRequest(reqId: Long,
                            exchange: Exchange,
                            request: ExchangeRequest) extends Tick


