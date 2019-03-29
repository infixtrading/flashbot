package flashbot.core

import flashbot.models.core.Account
import scalaz.{@@, Tag}
//import scalaz.{@@, Tag}

import scala.language.implicitConversions

sealed trait AssetKey[T] {
  def isAccount: Boolean
  def isSymbol: Boolean
  def security(t: T): String
  def exchangeOpt(t: T): Option[String]
  def withExchange(t: T, ex: String): Account
}


// Price path calculation can use either the straight symbol name ("btc") or it can specify
// an exchange too via an account, such as Account("bitmex", "xbt").

//sealed trait AssetKey extends Any with HasSecurity with MaybeHasAccount {
//  def isAccount: Boolean
//  def isSecurity: Boolean
//  def exchangeOpt: Option[String]
//  def withExchange(exchange: String): AssetKey
//}
//
//class AccountAsset(val account: Account) extends AnyVal with AssetKey {
//  override def isAccount = true
//  override def isSecurity = false
//  override def security = account.security
//  override def exchangeOpt = Some(account.exchange)
//  override def accountOpt = Some(account)
//  override def withExchange(exchange: String) = new AccountAsset(account.copy(exchange = exchange))
//  override def toString = account.toString
//}
//
//class SecurityAsset(val security: String) extends AnyVal with AssetKey {
//  override def isAccount = false
//  override def isSecurity = true
//  override def exchangeOpt = None
//  override def accountOpt = None
//  override def withExchange(exchange: String) =
//    new AccountAsset(Account(exchange, security))
//  override def toString = security
//}

object AssetKey {

  sealed trait AssetKeyTag
  type SecurityAsset = String @@ AssetKeyTag

  object implicits {
    implicit object AccountIsAsset extends AssetKey[Account] {
      override def isAccount = true
      override def isSymbol = false
      override def security(t: Account) = t.security
      override def exchangeOpt(t: Account) = Some(t.exchange)
      override def withExchange(t: Account, ex: String) = t.copy(exchange = ex)
    }

    implicit object SecurityIsAsset extends AssetKey[SecurityAsset] {
      override def isAccount = false
      override def isSymbol = true
      override def security(t: SecurityAsset) = t
      override def exchangeOpt(t: SecurityAsset) = None
      override def withExchange(t: SecurityAsset, ex: String) = Account(ex, t)
    }

    implicit def buildSecurityAsset(sym: String): SecurityAsset = Tag[String, AssetKeyTag](sym)
    implicit def unwrapSecurityAsset(tag: SecurityAsset): String = Tag.unwrap(tag)
  }

//  def isAccount[T: AssetKey](x: T): Boolean = implicitly[AssetKey[T]].isAccount

//  def apply(acc: Account): AccountAsset = buildAccountAsset(acc)
//  def apply(sym: String): SecurityAsset = buildSecurityAsset(sym)

//  type AccountAsset = Account @@ AssetKey
//  def buildAccountAsset(account: Account): AccountAsset = Tag[Account, AssetKey](account)
//  def unwrapAccountAsset(tag: AccountAsset): Account = Tag.unwrap(tag)
//
//
//

}
