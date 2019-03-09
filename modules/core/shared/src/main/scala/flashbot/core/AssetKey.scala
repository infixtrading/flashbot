package flashbot.core

import flashbot.models.core.Account

import scala.language.implicitConversions

// Price path calculation can use either the straight symbol name ("btc") or it can specify
// an exchange too via an account, such as Account("bitmex", "xbt").

class AssetKey(val value: Either[Account, String]) extends AnyVal
    with HasSecurity with MaybeHasAccount {

  def isAccount: Boolean = value.isLeft
  def isSecurity: Boolean = value.isRight

  def security: String = value match {
    case Left(acc) => acc.security
    case Right(sym) => sym
  }

  def exchangeOpt: Option[String] = Option(exchange)

  def exchange: String = value match {
    case Left(acc) => acc.exchange
    case Right(_) => null
  }

  def accountOpt: Option[Account] = Option(account)

  def account: Account = value match {
    case Left(acc) => acc
    case _ => null
  }

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
