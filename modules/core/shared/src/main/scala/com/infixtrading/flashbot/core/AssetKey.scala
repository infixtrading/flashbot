package com.infixtrading.flashbot.core
import com.infixtrading.flashbot.models.core.Account

// Price path calculation can use either the straight symbol name ("btc") or it can specify
// an exchange too via an account, such as Account("bitmex", "xbt").
//type AssetKey = Either[Account, String]

class AssetKey(val value: Either[Account, String]) extends AnyVal
    with HasSecurity with MaybeHasAccount {

  def security: String = value match {
    case Left(acc) => acc.security
    case Right(sym) => sym
  }

  def exchange: Option[String] = value.left.toOption.map(_.exchange)

  def account: Option[Account] = value.left.toOption

  override def toString = value match {
    case Left(acc) => acc.toString
    case Right(str) => str
  }

  def withExchange(exchange: String): AssetKey = AssetKey(Account(exchange, security))
}

object AssetKey {
  implicit def apply(acc: Account): AssetKey = new AssetKey(Left(acc))
  implicit def apply(sym: String): AssetKey = new AssetKey(Right(sym))
  def apply(exchange: String, security: String): AssetKey = AssetKey(Account(exchange, security))
}
