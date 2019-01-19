package com.infixtrading.flashbot.models.api

sealed trait DataServerMessage

/**
  * The message every data server sends to every other one to register itself across the cluster.
  */
case object RegisterDataServer extends DataServerMessage
