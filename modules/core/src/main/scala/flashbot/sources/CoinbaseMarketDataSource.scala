package flashbot.sources

import java.net.URI
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoField, ChronoUnit, TemporalAccessor}
import java.util.concurrent.{Executors, TimeUnit}

import akka.{Done, NotUsed}
import akka.actor.{ActorContext, ActorRef, PoisonPill}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{Flow, Keep, RestartSource, Sink, Source}
import flashbot.core.DataSource.IngestGroup
import flashbot.core.DataType.{CandlesType, LadderType, OrderBookType, TradesType}
import flashbot.core.Instrument.CurrencyPair
import flashbot.core._
import flashbot.models.core._
import flashbot.util.time.TimeFmt
import flashbot.util.stream._
import flashbot.util
import com.softwaremill.sttp.Uri.QueryFragment.KeyValue
import io.circe.generic.JsonCodec
import io.circe.{Json, Printer}
import io.circe.parser._
import io.circe.literal._
import io.circe.syntax._
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import com.softwaremill.sttp._
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import flashbot.core.DataType
import flashbot.models.core.Order._
import flashbot.models.core.OrderBook
import flashbot.server.RequestService._

import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success}

class CoinbaseMarketDataSource extends DataSource {

  import CoinbaseMarketDataSource._

  override def scheduleIngest(topics: Set[String], dataType: String) = {
    IngestGroup(topics, 0 seconds)
  }

  override def types = Seq(TradesType, OrderBookType, CandlesType(1 minute))

  override def ingestGroup[T](topics: Set[String], datatype: DataType[T])
                             (implicit ctx: ActorContext, mat: ActorMaterializer) = {

    implicit val ec: ExecutionContext = ctx.dispatcher

    val log = ctx.system.log
    log.debug("Starting ingest group {}, {}", topics, datatype)

    val (jsonRef, jsonSrc) = Source
      .actorRef[Json](Int.MaxValue, OverflowStrategy.fail)
      // Ignore any events that come in before the "subscriptions" event
      .dropWhile(eventType(_) != Subscribed)
      .preMaterialize()

    class FullChannelClient(uri: URI) extends WebSocketClient(uri) {
      override def onOpen(handshakedata: ServerHandshake) = {
        log.info("Coinbase WebSocket open")
      }

      override def onMessage(message: String) = {
        parse(message) match {
          case Left(err) =>
            log.error(err.underlying, "Parsing error in Coinbase Pro Websocket: {}", err.message)
            jsonRef ! PoisonPill
          case Right(jsonValue) =>
            jsonRef ! jsonValue
        }
      }

      override def onClose(code: Int, reason: String, remote: Boolean) = {
        log.info("Coinbase WebSocket closed")
        jsonRef ! PoisonPill
      }

      override def onError(ex: Exception) = {
        log.error(ex, "Exception in Coinbase Pro WebSocket. Shutting down the stream.")
        jsonRef ! PoisonPill
      }
    }

    val client = new FullChannelClient(new URI("wss://ws-feed.pro.coinbase.com"))

    // Complete this promise once we series a "subscriptions" message.
    val responsePromise = Promise[Map[String, Source[(Long, T), NotUsed]]]

    // Events are sent here as StreamItem instances
    val eventRefs = topics.map(_ ->
      Source.actorRef[StreamItem](Int.MaxValue, OverflowStrategy.fail).preMaterialize()).toMap

    datatype match {
      case OrderBookType =>
        // Asynchronously connect to the client and send the subscription message
        Future(blocking {
          if (!client.connectBlocking(30, SECONDS)) {
            responsePromise.failure(new RuntimeException("Unable to connect to Coinbase Pro WebSocket"))
          }
          val cbProducts: Set[String] = topics.map(toCBProduct)
          val strMsg = s"""
            {
              "type": "subscribe",
              "product_ids": ${cbProducts.asJson.noSpaces},
              "channels": ["full"]
            }
          """
          // Send the subscription message
          log.debug("Sending message: {}", strMsg)
          client.send(strMsg)
        })

        val snapshotPromises = topics.map(_ -> Promise[StreamItem]).toMap

        jsonSrc.alsoTo(Sink.foreach { _ =>
          if (!responsePromise.isCompleted) {
            // Complete the promise as soon as we have a "subscriptions" event
            responsePromise.success(eventRefs.map {
              case (topic, (ref, eventSrc)) =>
                val snapshotSrc = Source.fromFuture(snapshotPromises(topic).future)
                val (done, src: Source[(Long, T), NotUsed]) = eventSrc
                  .mergeSorted(snapshotSrc)(streamItemOrdering)
                  .via(util.stream.deDupeBy(_.seq))
                  .dropWhile(!_.isBook)
                  .scan[Option[StreamItem]](None) {
                    case (None, item) if item.isBook => Some(item)
                    case (Some(memo), item) if !item.isBook => Some(item.copy(data =
                      Left(OrderBook.Delta.fromOrderEventOpt(item.event)
                        .foldLeft(memo.book)(_ update _))))
                  }
                  .collect { case Some(item) if item.micros != -1 => (item.micros, item.book.asInstanceOf[T]) }
                  .watchTermination()(Keep.right).preMaterialize()
                done.onComplete(_ => {
                  ref ! PoisonPill
                })
                topic -> src
            })

            // Also kick off the snapshot requests.
            for (topic <- eventRefs.keySet) {
              val product = toCBProduct(topic)
              var uri = uri"https://api.pro.coinbase.com/products/$product/book?level=3"
              val snapRef = snapshotPromises(topic)
              sttp.get(uri).sendWithRetries().flatMap { rsp =>
                rsp.body match {
                  case Left(err) => Future.failed(new RuntimeException(s"Error in Coinbase snapshot request: $err"))
                  case Right(bodyStr) => Future.fromTry(decode[BookSnapshot](bodyStr).toTry)
                }
              } onComplete {
                case Success(snapshot) =>
                  snapRef.success(StreamItem(snapshot.sequence, -1, Left(snapshot.toOrderBook)))
                case Failure(err) =>
                  snapRef.failure(err)
              }
            }
          }
        })

        // Drop everything except for book events
        .filter(BookEventTypes contains eventType(_))

        // Map to StreamItem
        .map[StreamItem] { json =>
          val unparsed = json.as[UnparsedAPIOrderEvent].right.get
          val orderEvent = unparsed.toOrderEvent
          StreamItem(unparsed.sequence.get, unparsed.micros, Right(orderEvent))
        }

        // Send to event ref
        .runForeach { item => eventRefs(item.event.product)._1 ! item }

        // Shut down all event refs when stream completes.
        .onComplete { _ =>
          eventRefs.values.map(_._1).foreach(_ ! PoisonPill)
          try {
            client.close()
          } catch {
            case err: Throwable =>
              log.warning("An error occured while closing the Coinbase WebSocket connection: {}", err)
          }
        }

      case TradesType =>
        // Asynchronously connect to the client and send the subscription message
        Future(blocking {
          if (!client.connectBlocking(30, SECONDS)) {
            responsePromise.failure(new RuntimeException("Unable to connect to Coinbase Pro WebSocket"))
          }
          val cbProducts: Set[String] = topics.map(toCBProduct)
          val strMsg = s"""
            {
              "type": "subscribe",
              "product_ids": ${cbProducts.asJson.noSpaces},
              "channels": ["matches"]
            }
          """
          // Send the subscription message
          log.debug("Sending message: {}", strMsg)
          client.send(strMsg)
        })

        jsonSrc.alsoTo(Sink.foreach { json =>
          // Resolve promise if necessary
          if (!responsePromise.isCompleted) {
            responsePromise.success(eventRefs.map {
              case (topic, (ref, eventSrc)) =>
                val (done, src) = eventSrc
                  .map {
                    case StreamItem(seq, micros, Right(om: OrderMatch)) =>
                      (micros, om.toTrade.asInstanceOf[T])
                  }
                  .watchTermination()(Keep.right)
                  .preMaterialize()

                done.onComplete(_ => {
                  ref ! PoisonPill
                })
                topic -> src
            })
          }
        })

        // Drop everything except for book events
        .filter(BookEventTypes contains eventType(_))

        // Map to StreamItem
        .map[StreamItem] { json =>
          val unparsed = json.as[UnparsedAPIOrderEvent].right.get
          val orderEvent = unparsed.toOrderEvent
          StreamItem(unparsed.sequence.get, unparsed.micros, Right(orderEvent))
        }

        // Send to event ref
        .runForeach { item => eventRefs(item.event.product)._1 ! item }

        .onComplete { _ =>
          eventRefs.values.map(_._1).foreach(_ ! PoisonPill)
          try {
            client.close()
          } catch {
            case err: Throwable =>
              log.warning("An error occured while closing the Coinbase WebSocket connection: {}", err)
          }
        }

      case CandlesType(d) if d == 1.minute =>
        responsePromise.completeWith(ingestGroup(topics, TradesType).map(_.map {
          case (topic, src) =>
            topic -> src.map(_._2).map(t => (t.instant, t.price, t.size))
              .via(TimeSeriesTap.aggregateTrades(d).map(c => (c.micros, c.asInstanceOf[T])))
              .drop(1)
        }))
    }

    responsePromise.future
  }

  override def backfillPage[T](topic: String, datatype: DataType[T], cursorStr: Option[String])
                              (implicit ctx: ActorContext, mat: ActorMaterializer)
      : Future[(Vector[(Long, T)], Option[(String, FiniteDuration)])] = datatype match {
    case TradesType =>
      implicit val ec: ExecutionContext = ctx.dispatcher
      val cursor = cursorStr.map(decode[BackfillCursor](_).right.get)
      val product = toCBProduct(topic)
      var uri = uri"https://api.pro.coinbase.com/products/$product/trades"
      if (cursor.isDefined) {
        uri = uri.queryFragment(KeyValue("after", cursor.get.cbAfter))
      }

      sttp.get(uri).sendWithRetries().flatMap { rsp =>
        val nextCbAfterOpt = rsp.headers.toMap.get("cb-after").filterNot(_.isEmpty)
        rsp.body match {
          case Left(err) =>
            Future.failed(new RuntimeException(s"Error in Coinbase backfill: $err"))
          case Right(bodyStr) => Future.fromTry(decode[Seq[CoinbaseTrade]](bodyStr).toTry)

            // When you reach the end, it looks like they just return a list of the same trade.
            .map(_.toStream.dropDuplicates(Ordering.by(_.trade_id)).toVector)

            // Filter out any overlapping trades with prev page.
            .map(_.dropWhile(_.trade_id >=
              cursor.map(_.lastItemId.toLong).getOrElse[Long](Long.MaxValue)))

            // Map to page response.
            .map { trades =>
              val nextCursorOpt = for {
                nextCbAfter <- nextCbAfterOpt
                // This sets the cursor to None if `trades` is empty.
                lastTrade <- trades.lastOption
              } yield BackfillCursor(nextCbAfter, lastTrade.trade_id.toString)

              (trades.map(_.toTrade).map(t => (t.micros, t.asInstanceOf[T])),
                nextCursorOpt.map(x => (x.asJson.noSpaces, 4 seconds)))
            }
        }
      }

    case CandlesType(d) if d == 1.minute =>
      implicit val ec: ExecutionContext = ctx.dispatcher
      val product = toCBProduct(topic)
      val now = Instant.now()
      val endInstant = cursorStr.map(s =>
          Instant.from(DateTimeFormatter.ISO_INSTANT.parse(s)))
        .getOrElse(now)
      val end = DateTimeFormatter.ISO_INSTANT.format(endInstant)
      val start = DateTimeFormatter.ISO_INSTANT.format(endInstant.minusSeconds(60 * 200))
      val uri = uri"https://api.pro.coinbase.com/products/$product/candles?start=$start&end=$end&granularity=60"
      sttp.get(uri).sendWithRetries().flatMap { rsp =>
        rsp.body match {
          case Left(err) =>
            Future.failed(new RuntimeException(err))
          case Right(value) =>
            Future.fromTry(decode[Seq[(Long, Double, Double, Double, Double, Double)]](value).toTry)
              .map(rawData => {
                val data = rawData.toVector.map {
                  case (secs, low, high, open, close, volume) =>
                    val micros = secs * 1000000
                    (micros, Candle(micros, open, high, low, close, volume).asInstanceOf[T])
                }
                val cursor =
                  if (endInstant.isBefore(now.minus(4 * 365, ChronoUnit.DAYS))) None
                  else Some((start, 4 seconds))
                (data, cursor)
              })
        }
      }

    case _ => Future.failed(new UnsupportedOperationException(
      s"Coinbase backfill not implemented for $datatype."))
  }
}

object CoinbaseMarketDataSource {

  def toCBProduct(pair: String): String = pair.toUpperCase.replace("_", "-")

  case class StreamItem(seq: Long, micros: Long, data: Either[OrderBook, OrderEvent]) {
    def isBook: Boolean = data.isLeft
    def book: OrderBook = data.left.get
    def event: OrderEvent = data.right.get
  }

  val streamItemOrdering: Ordering[StreamItem] = new Ordering[StreamItem] {
    override def compare(x: StreamItem, y: StreamItem): Int = {
      if (x.seq < y.seq) -1
      else if (x.seq > y.seq) 1
      else if (x.isBook && !y.isBook) -1
      else if (!x.isBook && y.isBook) 1
      else 0
    }
  }

  val Open = "open"
  val Done = "done"
  val Received = "received"
  val Change = "change"
  val Match = "match"
  val Subscribed = "subscriptions"

  val BookEventTypes = List(Open, Done, Received, Change, Match)

  def eventType(json: Json): String =
    json.hcursor.get[String]("type").right.get

  // How we receive order events from the API. Fields are strings for some reason.
  @JsonCodec
  case class UnparsedAPIOrderEvent(`type`: String,
                                   product_id: String,
                                   sequence: Option[Long],
                                   time: Option[String],
                                   size: Option[String],
                                   price: Option[String],
                                   order_id: Option[String],
                                   side: Option[String],
                                   reason: Option[String],
                                   order_type: Option[String],
                                   remaining_size: Option[String],
                                   funds: Option[String],
                                   trade_id: Option[Long],
                                   maker_order_id: Option[String],
                                   taker_order_id: Option[String],
                                   taker_user_id: Option[String],
                                   user_id: Option[String],
                                   taker_profile_id: Option[String],
                                   profile_id: Option[String],
                                   new_size: Option[String],
                                   old_size: Option[String],
                                   new_funds: Option[String],
                                   old_funds: Option[String],
                                   last_size: Option[String],
                                   best_bid: Option[String],
                                   best_ask: Option[String],
                                   client_oid: Option[String]) {
    def toOrderEvent: OrderEvent = {
      `type` match {
        case Open =>
          OrderOpen(order_id.get, CurrencyPair(product_id), price.get.toDouble, remaining_size.get.toDouble, side.get)
        case Done =>
          OrderDone(order_id.get, CurrencyPair(product_id), side.get,
            DoneReason.parse(reason.get), price.map(_.toDouble), remaining_size.map(_.toDouble))
        case Change =>
          OrderChange(order_id.get, CurrencyPair(product_id), price.map(_.toDouble), new_size.get.toDouble)
        case Match =>
          OrderMatch(trade_id.get, CurrencyPair(product_id), micros, size.get.toDouble, price.get.toDouble,
            TickDirection.ofMakerSide(side.get), maker_order_id.get, taker_order_id.get)
        case Received =>
          OrderReceived(order_id.get, CurrencyPair(product_id), client_oid, OrderType.parseOrderType(order_type.get))
      }
    }

    def micros: Long = time.map(TimeFmt.ISO8601ToMicros).get
  }

  /**
    * {
    *   "time": "2014-11-07T22:19:28.578544Z",
    *   "trade_id": 74,
    *   "price": "10.00000000",
    *   "size": "0.01000000",
    *   "side": "buy"
    * }
    */
  @JsonCodec case class CoinbaseTrade(time: String, trade_id: Long, price: String, size: String, side: String) {
    implicit def toTrade: Trade = Trade(trade_id.toString,
      TimeFmt.ISO8601ToMicros(time), price.toDouble, size.toDouble, TickDirection.ofMakerSide(side))
  }

  @JsonCodec case class BackfillCursor(cbAfter: String, lastItemId: String)


  @JsonCodec case class BookSnapshot(sequence: Long,
                                     asks: Seq[(String, String, String)],
                                     bids: Seq[(String, String, String)]) {
    def toOrderBook: OrderBook = {
      val withAsks = asks.foldLeft(OrderBook()) {
        case (book, askSeq) => book.open(askSeq._3, askSeq._1.toDouble, askSeq._2.toDouble, Sell)
      }
      bids.foldLeft(withAsks) {
        case (book, askSeq) => book.open(askSeq._3, askSeq._1.toDouble, askSeq._2.toDouble, Buy)
      }
    }
  }
}
