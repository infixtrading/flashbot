package com.infixtrading.flashbot.api

sealed trait FlashbotReq

object FlashbotReq {
  case class IndexReq(msg: String) extends FlashbotReq
}
