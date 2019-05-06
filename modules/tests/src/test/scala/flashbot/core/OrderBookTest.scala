package flashbot.core

import flashbot.models.Order._
import flashbot.models.OrderBook
import org.scalatest.{FlatSpec, Matchers}
import io.circe.syntax._
import io.circe.parser._

class OrderBookTest extends FlatSpec with Matchers {
  "OrderBook" should "encode to JSON" in {
    val book = new OrderBook(.01)
      .open("1", 2.3, 3.3, Buy)
      .open("2", 4.4, 4.5, Sell)

    val bookType = DataType("book")
    val fmt = bookType.fmtJson[OrderBook]
    val bookJson = fmt.modelEn(book)
    val decoded = bookJson.as[OrderBook](fmt.modelDe).right.get
    decoded shouldEqual book
  }
}
