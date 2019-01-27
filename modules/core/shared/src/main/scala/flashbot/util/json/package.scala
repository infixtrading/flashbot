package flashbot.util

import io.circe._
import io.circe.parser._
import io.circe.syntax._

package object json {

  def parseJson[T](jsonStr: String)(implicit d: Decoder[T]): Either[String, T] = {
    parse(jsonStr) match {
      case Left(y) => Left("Could not parse JSON string: " ++ jsonStr)
      case Right(x) =>
        x.as[T] match {
          case Left(err) => Left(s"${err.message}: $jsonStr")
          case Right(ev) => Right(ev)
        }
    }
  }

  val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
  def printJson(json: Json): String = printer.pretty(json)
  def printJson[T](t: T)(implicit en: Encoder[T]): String = printJson(t.asJson)

}
