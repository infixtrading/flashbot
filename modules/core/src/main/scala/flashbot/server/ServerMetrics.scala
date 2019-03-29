package flashbot.server

import java.util.concurrent.ConcurrentHashMap

import flashbot.core.{Closable, Metrics}
import io.prometheus.client.{Collector, Counter, Summary}

class ClosableTimer(val timer: Summary.Timer) extends AnyVal with Closable {
  override def close() = timer.close()
}

object ServerMetrics extends Metrics {
  val allMetrics = new ConcurrentHashMap[String, Collector]

  override def observe(metricName: String, value: Double, labels: Map[String, String]): Unit = {
    val labelNames = labels.keys.toSeq.sorted
    val labelValues = labelNames.map(labels(_))
    val summary = allMetrics
      .computeIfAbsent(metricName, name =>
        Summary.build(name, name).labelNames(labelNames:_*).register())
      .asInstanceOf[Summary]
    summary.labels(labelValues:_*).observe(value)
  }

  override def startTimer(metricName: String, labels: Map[String, String]): Closable = {
    val labelNames = labels.keys.toSeq.sorted
    val labelValues = labelNames.map(labels(_))
    val summary = allMetrics
      .computeIfAbsent(metricName, name =>
        Summary.build(name, name).labelNames(labelNames:_*).register())
      .asInstanceOf[Summary]
    new ClosableTimer(summary.labels(labelValues:_*).startTimer())
  }

  def inc(metricName: String, labels: Map[String, String], value: Double): Unit = {
    val labelNames = labels.keys.toSeq.sorted
    val labelValues = labelNames.map(labels(_))
    val counter = allMetrics
      .computeIfAbsent(metricName, name =>
        Counter.build(name, name).labelNames(labelNames:_*).register())
      .asInstanceOf[Counter]
    counter.labels(labelValues:_*).inc(value)
  }
}
