package flashbot.core

case class Account(exchange: String, security: String) extends HasSecurity {
  override def toString = s"$exchange.$security"
}
