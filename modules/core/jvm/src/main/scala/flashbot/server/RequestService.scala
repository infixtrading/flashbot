package flashbot.server

import java.util.concurrent.{Executors, ThreadPoolExecutor}

import akka.stream.Materializer
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.softwaremill.sttp.{Request, Response, SttpBackend}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.{Failure, Success}

object RequestService {

  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(30))

  private implicit val okHttpBackend: SttpBackend[Future, Nothing] =
    OkHttpFutureBackend()(ec)

  def retry429[T](rsp: Response[T]) = rsp.code == 429

  implicit class RequestOps[T](req: Request[T, Nothing]) {
    def sendWithRetries(minBackoff: FiniteDuration = 1 second,
                        maxBackoff: FiniteDuration = 60 seconds,
                        randomFactor: Double = 0.2,
                        retryIf: Response[T] => Boolean = retry429)
                       (implicit mat: Materializer): Future[Response[T]] = {
      RestartSource.onFailuresWithBackoff(
        minBackoff = minBackoff,
        maxBackoff = maxBackoff,
        randomFactor = randomFactor
      ){ () =>
        Source.fromFuture(req.send().transform {
          case suc @ Success(rsp) =>
            if (retryIf(rsp))
              Failure(new RuntimeException(s"Request must be retried for ${req.uri}."))
            else suc
          case fail => fail
        })
      }.runWith(Sink.head)
    }
  }
}
