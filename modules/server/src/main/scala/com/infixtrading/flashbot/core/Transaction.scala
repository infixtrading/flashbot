package com.infixtrading.flashbot.core

sealed trait Transaction

case class TradeTx() extends Transaction
case class Withdraw() extends Transaction
case class Deposit() extends Transaction
