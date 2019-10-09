package flashbot.sources

import java.net.URI

import akka.NotUsed
import akka.actor.{ActorContext, ActorRef, PoisonPill}
import akka.event.LoggingAdapter
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.Source
import breeze.numerics.log
import flashbot.core.{Ask, Bid, DataSource, DataType}
import flashbot.models.Ladder
import flashbot.util.time
import io.circe.Json
import io.circe.generic.JsonCodec
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import io.circe.parser._
import com.softwaremill.sttp._
import flashbot.models.Ladder.LadderDelta
import flashbot.util.network.RequestService._
import flashbot.util.network.WebSocketStreamClient

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration._

class BitMEXMarketDataSource extends DataSource {
  import BitMEXMarketDataSource._

  override def ingest[T](topic: String, datatype: DataType[T])
                        (implicit ctx: ActorContext, mat: ActorMaterializer)
      : Future[Source[(Long, T), NotUsed]] = {

    implicit val ec: ExecutionContext = ctx.dispatcher

    val (msgRef, msgSrc) = Source
      .actorRef[(Long, L2Message)](Int.MaxValue, OverflowStrategy.fail)
      .preMaterialize()

    class L2ChannelClient(topic: String, instrumentInfo: InstrumentInfo)
      extends WebSocketStreamClient(new URI(s"wss://www.bitmex.com/realtime?subscribe=orderBookL2:$topic")) {

      override def sourceRef: ActorRef = msgRef
      override def log: LoggingAdapter = ctx.system.log
      override def name: String = "BitMEX"

      var subscribed = false
      var seenSnapshot = false

      override def onMessageSafe(message: String): Unit = {
        val now = time.currentTimeMicros
        parse(message) match {
          case Left(err) =>
            log.error(err.underlying, "Parsing error in BitMEX Websocket: {}", err.message)
            msgRef ! PoisonPill

          case Right(jsonValue) =>
            if (!subscribed) {
              subscribed = jsonValue.as[SubscribedMessage].isRight
            } else {
              val msgResult = jsonValue.as[L2Message]
              if (msgResult.isRight) {
                msgRef ! (now, msgResult.right.get)
              } else {
                log.error(msgResult.left.get, "Failed to decode the following message: {}", message)
                throw msgResult.left.get
              }
            }
        }
      }
    }

    val instrumentsURI = uri"https://www.bitmex.com/api/v1/instrument?columns=symbol,tickSize&start=0&count=500"
    for {
      instruments <- sttp.get(instrumentsURI).sendWithRetries().flatMap { rsp =>
        rsp.body match {
          case Left(err) => Future.failed(new RuntimeException(s"Error in BitMEX Instruments Request: $err"))
          case Right(bodyStr) => Future.fromTry(decode[Seq[Instrument]](bodyStr).toTry)
        }
      }
      instrumentInfo = new InstrumentInfo(topic, instruments)
      client = new L2ChannelClient(topic, instrumentInfo)
      resultStream <- Future(blocking {
        if (!client.connectBlocking(30, SECONDS)) {
          throw new RuntimeException("Unable to connect to BitMEX WebSocket")
        }
        msgSrc.scan((0L, new Ladder(20000, instrumentInfo.tickSize))) {
          case ((prevMicros, ladder), (micros, msg)) =>
            val deltas = msg.data.map { pl =>
              val side = if (pl.side == "Sell") Ask else Bid
              val price = instrumentInfo.idToPrice(pl.id)
              LadderDelta(side, price, pl.size.getOrElse(0L).toDouble)
            }

            if (prevMicros == 0) {
              deltas.foreach(d => {
                ladder.updateLevel(d.side, d.priceLevel, d.quantity)
              })
              (micros, ladder)
            } else {
              (micros, ladder.update(deltas))
            }
        }.drop(1).asInstanceOf[Source[(Long, T), NotUsed]]
      })
    } yield resultStream
  }
}

object BitMEXMarketDataSource {

  @JsonCodec
  case class SubscribedMessage(success: Boolean, subscribe: String)

  @JsonCodec
  case class L2Message(table: String, action: String, data: Seq[PriceLevel])

  @JsonCodec
  case class PriceLevel(symbol: String, id: Long, side: String, size: Option[Long])

  @JsonCodec
  case class Instrument(symbol: String, timestamp: String, tickSize: Double)

  class InstrumentInfo(symbol: String, instruments: Seq[Instrument]) {
    private val instrumentIndex: Long = instruments.indexWhere(_.symbol == symbol)
    assert(instrumentIndex >= 0, s"Instrument $symbol not found")

    private val instrument = instruments(instrumentIndex.toInt)

    // Legacy tick size for XBTUSD, actual tickSize for all others.
    private val tickSizeForIDCalc: Double = if (symbol == "XBTUSD") .01 else instrument.tickSize

    // https://www.bitmex.com/app/restAPI#OrderBookL2
    def idToPrice(id: Long): Double = (100000000L * instrumentIndex - id) * tickSizeForIDCalc
    def priceToId(price: Double): Long = (100000000L * instrumentIndex) - (price / tickSizeForIDCalc).toLong

    def tickSize: Double = instrument.tickSize
  }
}
