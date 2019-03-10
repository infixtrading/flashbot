package flashbot.core

import flashbot.models.core.Account

import scala.language.implicitConversions

// Price path calculation can use either the straight symbol name ("btc") or it can specify
// an exchange too via an account, such as Account("bitmex", "xbt").

sealed trait AssetKey extends Any with HasSecurity with MaybeHasAccount {
  def isAccount: Boolean
  def isSecurity: Boolean
  def exchangeOpt: Option[String]
  def withExchange(exchange: String): AssetKey
}

class AccountAsset(val account: Account) extends AnyVal with AssetKey {
  override def isAccount = true
  override def isSecurity = false
  override def security = account.security
  override def exchangeOpt = Some(account.exchange)
  override def accountOpt = Some(account)
  override def withExchange(exchange: String) = new AccountAsset(account.copy(exchange = exchange))
  override def toString = account.toString
}

class SecurityAsset(val security: String) extends AnyVal with AssetKey {
  override def isAccount = false
  override def isSecurity = true
  override def exchangeOpt = None
  override def accountOpt = None
  override def withExchange(exchange: String) =
    new AccountAsset(Account(exchange, security))
  override def toString = security
}

object AssetKey {
  implicit def apply(acc: Account): AccountAsset = new AccountAsset(acc)
  implicit def apply(sym: String): SecurityAsset = new SecurityAsset(sym)
}
