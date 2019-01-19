package com.infixtrading.flashbot.sources

import akka.actor.ActorContext
import akka.stream.ActorMaterializer
import com.infixtrading.flashbot.core.DataType.OrderBookType
import com.infixtrading.flashbot.core.{DataSource, DataType}

class CoinbaseMarketDataSource extends DataSource {
  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer) = {
    datatype match {
      case OrderBookType => ???
    }
  }
}
