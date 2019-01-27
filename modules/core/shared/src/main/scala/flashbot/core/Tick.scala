package flashbot.core

case class Tick(events: Seq[Any] = Seq.empty, exchange: Option[String] = None)

