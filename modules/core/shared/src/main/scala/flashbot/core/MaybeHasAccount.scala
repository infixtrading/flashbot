package flashbot.core

import flashbot.models.core.Account

trait MaybeHasAccount extends Any {
  def accountOpt: Option[Account]
}

