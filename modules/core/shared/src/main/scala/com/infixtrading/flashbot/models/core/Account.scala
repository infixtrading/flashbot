package com.infixtrading.flashbot.models.core

import io.circe._
import io.circe.generic.semiauto._

case class Account(exchange: String, security: String) {
  override def toString = s"$exchange/$security"
}
object Account {
  def apply(str: String): Account = parse(str)

  implicit def parse(acc: String): Account = {
    val parts = acc.split("/")
    Account(parts(0), parts(1))
  }

  implicit def en: Encoder[Account] = deriveEncoder[Account]
  implicit def de: Decoder[Account] = deriveDecoder[Account]

  implicit val accountKeyEncoder: KeyEncoder[Account] = new KeyEncoder[Account] {
    override def apply(key: Account) = key.toString
  }

  implicit val accountKeyDecoder: KeyDecoder[Account] = new KeyDecoder[Account] {
    override def apply(key: String) = Some(parse(key))
  }
}

