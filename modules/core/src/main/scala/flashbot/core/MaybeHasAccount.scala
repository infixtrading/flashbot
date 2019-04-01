package flashbot.core

import flashbot.models.Account

trait MaybeHasAccount extends Any {
  def accountOpt: Option[Account]
}

