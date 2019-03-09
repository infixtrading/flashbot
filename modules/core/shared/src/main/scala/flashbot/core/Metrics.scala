package flashbot.core

trait Closable extends Any {
  def close(): Unit
}

trait Metrics {
  def observe(metricName: String, value: Double, labels: Map[String, String] = Map.empty): Unit
  def startTimer(metricName: String, labels: Map[String, String] = Map.empty): Closable
  def inc(metricName: String, labels: Map[String, String] = Map.empty, value: Double = 1): Unit
}
