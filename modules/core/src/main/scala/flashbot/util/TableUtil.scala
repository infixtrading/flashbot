package flashbot.util

import java.io.ByteArrayOutputStream

import com.github.agourlay.json2Csv.Json2Csv
import io.circe.{Encoder, Json, JsonObject}
import io.circe.syntax._
import com.bethecoder.ascii_table
import com.bethecoder.ascii_table.ASCIITable
import flashbot.models.Ladder

object TableUtil {
  def render[T: Encoder](items: Seq[T]): Unit = {
    val out = new ByteArrayOutputStream()
    val stream = items.map(_.asJson.noSpaces).toStream
    Json2Csv.convert(stream, out)
    val rows = out.toString.split("\r\n").toVector
    ASCIITable.getInstance().printTable(rows.head.split(","), rows.drop(1).toArray.map(_.split(",")))
  }

  def renderLadder(ladder: Ladder): Unit = {
    val depth = 10
    val data = Array((1 to (depth * 2 + 1)).map(_ => Array("", "", "", "")):_*)

    var askTotal: Double = 0
    ladder.asks.iterator().take(depth).zipWithIndex.foreach {
      case ((price, size), i) =>
        val idx = depth - 1 - i
        data(idx)(0) = price.toString
        data(idx)(1) = size.toString
        askTotal += size
        data(idx)(2) = askTotal.toString
        data(idx)(3) = "|" * (askTotal / 20000).toInt
    }

    var bidTotal: Double = 0
    ladder.bids.iterator().take(depth).zipWithIndex.foreach {
      case ((price, size), i) =>
        val idx = depth + 1 + i
        data(idx)(0) = price.toString
        data(idx)(1) = size.toString
        bidTotal += size
        data(idx)(2) = bidTotal.toString
        data(idx)(3) = "|" * (bidTotal / 20000).toInt
    }

    ASCIITable.getInstance().printTable(Array("Price", "Size", "Total", "Depth"), data, -1)
  }
}
