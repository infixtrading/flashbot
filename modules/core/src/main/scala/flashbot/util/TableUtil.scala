package flashbot.util

import java.io.ByteArrayOutputStream

import com.github.agourlay.json2Csv.Json2Csv
import io.circe.Encoder
import io.circe.syntax._
import com.bethecoder.ascii_table
import com.bethecoder.ascii_table.ASCIITable

import scala.collection.JavaConverters._

object TableUtil {
  def print[T: Encoder](items: Seq[T]): Unit = {
    val out = new ByteArrayOutputStream()
    val stream = items.map(_.asJson.noSpaces).toStream
    Json2Csv.convert(stream, out)

    val rows = out.toString.split("\r\n").toVector

    ASCIITable.getInstance().printTable(rows.head.split(","), rows.drop(1).toArray.map(_.split(",")))
  }
}
