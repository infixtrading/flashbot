package com.infixtrading.flashbot.models.core

import com.infixtrading.flashbot.core.HasSecurity
import io.circe._
import io.circe.generic.semiauto._

case class Account(exchange: String, security: String) extends HasSecurity {
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

  implicit val accountKeyEncoder: KeyEncoder[Account] = KeyEncoder.instance(_.toString)

  implicit val accountKeyDecoder: KeyDecoder[Account] = KeyDecoder.instance(x => Some(parse(x)))
}

