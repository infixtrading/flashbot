package flashbot.server

import java.util.concurrent.ConcurrentHashMap

import io.prometheus.client.{Collector, Counter, Summary}

object Metrics {
  val allMetrics = new ConcurrentHashMap[String, Collector]


  def observe(metricName: String, value: Double): Unit = {
    val summary = allMetrics
      .computeIfAbsent(metricName, name => Summary.build(name, name).register())
      .asInstanceOf[Summary]
    summary.observe(value)
  }

  def startTimer(metricName: String): Summary.Timer = {
    val summary = allMetrics
      .computeIfAbsent(metricName, name => Summary.build(name, name).register())
      .asInstanceOf[Summary]
    summary.startTimer()
  }

  def inc(metricName: String): Unit = {
    val counter = allMetrics
      .computeIfAbsent(metricName, name => Counter.build(name, name).register())
      .asInstanceOf[Counter]
    counter.inc()
  }

  def inc(metricName: String, value: Double): Unit = {
    val counter = allMetrics
      .computeIfAbsent(metricName, name => Summary.build(name, name).register())
      .asInstanceOf[Counter]
    counter.inc(value)
  }
}
