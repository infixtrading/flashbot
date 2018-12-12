package com.infixtrading.flashbot.util.time

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date

object TimeFmt {
 private val formatter = DateTimeFormatter.ISO_DATE_TIME

 def ISO8601ToMicros(str: String): Long = {
   val inst = Instant.from(formatter.parse(str))
   inst.getEpochSecond * 1000000 + inst.getNano / 1000
 }

 private val timeFmt = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss")

 def formatDate(date: Date): String = timeFmt.format(date)
}
