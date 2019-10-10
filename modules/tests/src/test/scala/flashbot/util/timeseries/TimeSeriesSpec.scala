package flashbot.util.timeseries

import java.time.Instant

import flashbot.core.{OrderBookTap, Trade}
import flashbot.models.Candle
import flashbot.models.Order.{Down, Up}
import org.scalatest.{FlatSpec, Matchers}
import flashbot.util.timeseries.Implicits._
import flashbot.util.timeseries.Scannable.BaseScannable._
import flashbot.util.timeseries.Scannable.BaseFoldable._
import org.ta4j.core.Bar
import flashbot.util.time._
import flashbot.util.timeseries
import flashbot.util.timeseries.TimeSeriesFactory.Bars

import scala.concurrent.duration._

class TimeSeriesSpec extends FlatSpec with Matchers {
  val nowMillis = 1543017219051L // A few minutes before midnight
  val MicrosPerMinute: Long = 60L * 1000000

  "TimeSeriesLike" should "create a ta4j time series from an iterable of trades" in {
    val nowMicros = nowMillis * 1000
    val trades: Seq[Trade] = (1 to 1440) map { i =>
      Trade(i.toString, nowMicros + i * MicrosPerMinute, i % 33, i % 40, if (i % 2 == 0) Up else Down)
    }

    val ts = trades.map(t => (t.instant, t.price, t.size))

    val foo = timeseries.scanVia(ts.iterator.toIterable, 1.hour, Bars)

    foo.foreach(println)
  }
}
