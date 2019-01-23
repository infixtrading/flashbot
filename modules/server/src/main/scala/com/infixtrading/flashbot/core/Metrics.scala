package com.infixtrading.flashbot.core

import io.prometheus.client.{Collector, Counter, Summary}

import scala.collection.concurrent.TrieMap

object Metrics {
  val allMetrics = TrieMap.empty[String, Collector]

  def observe(metricName: String, value: Double): Unit = {
    val summary = allMetrics
      .getOrElseUpdate(metricName, Summary.build(metricName, metricName).register())
      .asInstanceOf[Summary]
    summary.observe(value)
  }

  def startTimer(metricName: String): Summary.Timer = {
    val summary = allMetrics
      .getOrElseUpdate(metricName, Summary.build(metricName, metricName).register())
      .asInstanceOf[Summary]
    summary.startTimer()
  }

  def inc(metricName: String): Unit = {
    val counter = allMetrics
      .getOrElseUpdate(metricName, Counter.build(metricName, metricName).register())
      .asInstanceOf[Counter]
    counter.inc()
  }

  def inc(metricName: String, value: Double): Unit = {
    val counter = allMetrics
      .getOrElseUpdate(metricName, Summary.build(metricName, metricName).register())
      .asInstanceOf[Counter]
    counter.inc(value)
  }
}
