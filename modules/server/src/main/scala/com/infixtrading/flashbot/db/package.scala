package com.infixtrading.flashbot
import java.sql.{Connection, DriverManager}

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.infixtrading.flashbot.core.DeltaFmtJson
import com.infixtrading.flashbot.models.core.DataPath

import scala.concurrent.Future

package object db {
  def createSnapshotsTableIfNotExists(jdbcUrl: String): Unit = {
    val sql = ""
    val connection = DriverManager.getConnection(jdbcUrl)
    val statement = connection.createStatement()
    statement.execute(sql)
    connection.close()
  }

  def createDeltasTableIfNotExists(jdbcUrl: String): Unit = {}

  def createBundle(jdbcUrl: String, path: DataPath): Long = ???

  def streamBundle[T](id: Long, fromMicros: Long)
                     (implicit fmt: DeltaFmtJson[T]): Source[T, NotUsed] = ???

  def ingestItemsAsync[T](bundleId: Long,
                          items: Seq[((Long, T), Long)]): Future[Long] = ???

}
