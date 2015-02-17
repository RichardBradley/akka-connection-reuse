package myproject

import akka.pattern.ask
import akka.actor.{ActorRefFactory, ActorRef, ActorSystem}
import akka.http.marshalling.PredefinedToResponseMarshallers._
import akka.http.marshalling.ToResponseMarshallable
import akka.http.model.HttpMethods._
import akka.http.model._
import akka.http.server._
import akka.pattern.after
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestActorRef
import akka.util.{ByteString, Timeout}
import java.net.InetSocketAddress
import myproject.EarlyHttpResponsesSpec.LargeBodySize
import myproject.EarlyHttpResponsesSpec.earlyHttpResponsesSpecRoute
import myproject.EarlyHttpResponsesSpec.UploadRequestBehaviour.UploadRequestBehaviour
import myproject.EarlyHttpResponsesSpec.UploadRequestBehaviour.FailAfterReadingHalfOfBody
import myproject.EarlyHttpResponsesSpec.UploadRequestBehaviour.FailAfterReadingNoneOfBody
import myproject.EarlyHttpResponsesSpec.UploadRequestBehaviour.FailAfterReadingWholeBody
import myproject.HttpServerBase.{Unbind, Bind, Bound}
import myproject.http.JavaUrlConnectionHttpClient
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import Directives._
import TestBlockingHttpEntityFetcher._
import myproject.utils.Uris._

/**
 * There are lots of subtle issues surrounding replying early to HTTP requests.
 * We need to ensure that either the TCP upload stream is closed and/or the
 * entire request is read in (and discarded) otherwise the TCP stream will
 * stall and the server will appear unresponsive.
 *
 * Bad behaviour in this regard may be masked by TCP read/write buffering, so
 * may not be immediately obvious.
 *
 * See https://groups.google.com/forum/#!topic/akka-user/MK8Ug9vV2K4
 */
class EarlyHttpResponsesSpec
  extends Specification
  with NoTimeConversions {

  private implicit val system = ActorSystem()

  "a failed upload" should {

    "leave the stream usable if the whole request body was read" in new Scope {
      startServer()

      val responses = sendRequestsOnSameConnection(
        List.fill(10)(largeUploadRequest(expected = FailAfterReadingWholeBody)))

      forall(responses) { res =>
        res.status mustEqual StatusCodes.BadRequest
        res.entity.asString mustEqual "test response body"
      }
    }

    "leave the stream usable if half of the request body was read" in new Scope {
      // TODO:https://github.com/akka/akka/issues/16893 -- to fix this we need to fix line 142 in HttpServer

      startServer()

      val responses = sendRequestsOnSameConnection(
        List.fill(10)(largeUploadRequest(expected = FailAfterReadingHalfOfBody)))

      forall(responses) { res =>
        res.status mustEqual StatusCodes.BadRequest
        res.entity.asString mustEqual "test response body"
      }
    }

    "leave the stream usable if none of the request body was read" in new Scope {
      startServer()

      val responses = sendRequestsOnSameConnection(
        List.fill(10)(largeUploadRequest(expected = FailAfterReadingNoneOfBody)))

      forall(responses) { res =>
        res.status mustEqual StatusCodes.BadRequest
        res.entity.asString mustEqual "test response body"
      }
    }
  }

  private def sendRequestsOnSameConnection(
    requests: List[HttpRequest]
  ): Seq[HttpResponse] = {

    // The JavaUrlConnection doesn't allow us fine-grained control over which
    // connections are used.
    // However, if the following conditions are met, then we can be sure that
    // a connection will have been reused:
    //  - more than 5 requests
    //   (see 'http.maxConnections' in sun/net/www/http/KeepAliveCache)
    require(requests.length > 5)
    //  - all responses are empty or small enough to be read in full
    //   (see 'http.finished()' call on line 1360 of
    //    sun/net/www/http/HttpURLConnection.java which puts the connection
    //    back into the KeepAliveCache)
    ///   and ?? for the 'or'

    val client = new JavaUrlConnectionHttpClient()

    // (don't use Future.traverse, as we want serial execution here)
    requests.zipWithIndex.map { case (request, idx) =>
      Thread.sleep(100)
      try {
        Await.result(client.sendRequest(request), 30.seconds)
          .mapEntity((e: ResponseEntity) => fetchEntity(e)(implicitly[ActorRefFactory]))
      } catch {
        case e: Throwable =>
          throw new Exception(s"For request ${idx+1} of ${requests.length}", e)
      }
    }

//    // TODO:https://github.com/akka/akka/issues/16865 use Akka's client when they fix it
//    // https://groups.google.com/forum/#!topic/akka-user/Cw-cx1BnHDk
//    implicit val fm = ActorFlowMaterializer()
//    val http = Http()
//    val connection = http.outgoingConnection(
//      "127.0.0.1", serverAddress.getPort)
//
//    val responses = Source(requests.toList)
//      .via(connection.flow)
//      .runWith(Sink.fold(List[HttpResponse]())(_ :+ _))
//
//    Await.result(responses, config.clientRequestTimeout.duration)
  }

  private class Scope extends org.specs2.specification.Scope {

    var serverRef: ActorRef = _
    var serverAddress: InetSocketAddress = _

    def serverUri: Uri = "http://127.0.0.1:" + serverAddress.getPort

    def largeUploadRequest(expected: UploadRequestBehaviour): HttpRequest = {
      HttpRequest(
        method = POST,
        uri = serverUri / expected.toString,
        entity = HttpEntity(("x" * 63 + "\n") * (LargeBodySize / 64)))
    }

    def smallRequest: HttpRequest = {
      HttpRequest(
        method = POST,
        uri = serverUri / "success",
        entity = HttpEntity("x" * 2048))
    }

    def startServer(): Unit = {
      require(serverRef == null)
      serverRef = TestActorRef(
        new HttpServer(
          earlyHttpResponsesSpecRoute(system)) {

        override def port = 0 // choose a free port
      })

      implicit val startTimeout: Timeout = 10.seconds
      val addressF = (serverRef ? Bind).map {
        case Bound(address) => address
      }
      serverAddress = Await.result(addressF, 10.seconds)
    }

    def stopServer(): Unit = {
      serverRef ! Unbind
    }
  }
}

object EarlyHttpResponsesSpec {
  object UploadRequestBehaviour extends Enumeration {
    type UploadRequestBehaviour = Value

    val FailAfterReadingWholeBody = Value
    val FailAfterReadingHalfOfBody = Value
    val FailAfterReadingNoneOfBody = Value
  }

  val LargeBodySize = 1024 * 1024

  def earlyHttpResponsesSpecRoute(implicit system: ActorSystem): Route = {
    import system.dispatcher
    implicit val fm = ActorFlowMaterializer()

    path("FailAfterReadingWholeBody") {
      extractRequest { request =>
        complete {
          after(100.millis, system.scheduler) {
            val body: String = request.entity.asString
            require(body.length == LargeBodySize, s"found ${body.length}, required ${LargeBodySize}")
            Future {
              HttpResponse(
                StatusCodes.BadRequest,
                entity = "test response body"): ToResponseMarshallable
            }
          }
        }
      }
    } ~ path("FailAfterReadingHalfOfBody") {
      extractRequest { request =>
        complete {
          // This is a bit contrived, but a lot of the multipart-MIME unmarshallers will
          // do 'prefixAndTail'.
          request.entity.dataBytes.prefixAndTail(1).runWith(Sink.head) flatMap {
            case (Seq(first: ByteString), tailSource: Source[_]) =>
              val bodySoFar = first.utf8String
              require(bodySoFar.length > 0)
              require(bodySoFar.length < LargeBodySize)
              after(100.millis, system.scheduler) {
                Future {
                  HttpResponse(
                    StatusCodes.BadRequest,
                    entity = "test response body"): ToResponseMarshallable
                }
              }
          }
        }
      }
    } ~ path("FailAfterReadingNoneOfBody") {
      complete {
        after(100.millis, system.scheduler) {
          Future {
            HttpResponse(
              StatusCodes.BadRequest,
              entity = "test response body")
          }
        }
      }
    } ~ path("success") {
      extractRequest { request =>
        complete {
          request.entity.asString
          after(100.millis, system.scheduler) {
            Future {
              HttpResponse(StatusCodes.OK, entity = "response body")
            }
          }
        }
      }
    }
  }
}
