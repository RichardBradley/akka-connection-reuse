package myproject.http

import akka.actor._
import akka.event.Logging
import akka.http.model.Uri.Query
import akka.http.model._
import akka.http.model.headers.{RawHeader, `Content-Length`, `Content-Type`}
import akka.http.model.parser.HeaderParser
import akka.stream.ActorFlowMaterializer
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import java.io.{IOException, InputStream}
import java.net.HttpURLConnection
import myproject.http.JavaUrlConnectionHttpClient.ResponsePublisher
import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * A class which offers a [[HttpRequest]] to [[HttpResponse]] interface, much
 * like Akka's client, but is based on java's [[HttpURLConnection]].
 *
 * This is required as we exhaust client ports during test runs due to a lack
 * of connection pooling in akka.
 *
 * This client does support streamed and chunked requests, but note that the
 * behaviour may not be identical to an akka client as many of the internals
 * are out of our control.
 *
 * TODO: This should be removed when akka supports connection pooling.
 */
class JavaUrlConnectionHttpClient()(
    implicit actorSystem: ActorSystem) {

  private val log = Logging(actorSystem, getClass)

  def sendRequest(request: HttpRequest): Future[HttpResponse] = {
    try {
      log.debug(s"Sending request to ${request.method.name} ${request.uri}")

      val connection = setupConnection(request)
      sendRequest(connection, request)
        .flatMap { _ => readResponse(connection) }
    } catch {
      case NonFatal(e) => Future.failed(e)
    }
  }

  private def setupConnection(
    request: HttpRequest
  ): HttpURLConnection = {
    val url = if (request.method == HttpMethods.PATCH)
      // We use a query string to indicate a PATCH request because:
      //  * HttpUrlConnection doesn't support PATCH requests
      //  * akka routing has a helper for doing this, rather than using the
      //    more usual X-Http-Method-Override header.
      request.uri.withQuery(Query(request.uri.query :+ ("http_method", "PATCH"):_*)).toString()
    else
      request.uri.toString()
    val connection = new java.net.URL(url).openConnection()
      .asInstanceOf[HttpURLConnection]
    val socketTimeoutMillis = 2000
    connection.setConnectTimeout(socketTimeoutMillis)
    if (request.method.isEntityAccepted)
      connection.setDoOutput(true)
    connection.setDoInput(true)
    connection.setInstanceFollowRedirects(false)
    if (request.method == HttpMethods.PATCH)
      connection.setRequestMethod("POST")
    else
      connection.setRequestMethod(request.method.value)
    connection
  }

  private def sendRequest(
    connection: HttpURLConnection,
    request: HttpRequest
  ): Future[Unit] = {
    setRequestHeaders(connection, request)
    sendRequestBody(connection, request)
  }

  private def setRequestHeaders(connection: HttpURLConnection, request: HttpRequest): Unit = {
    request.headers.foreach { h =>
      connection.setRequestProperty(h.name(), h.value())
    }
    if (request.entity.contentType() != ContentTypes.NoContentType) {
      connection.setRequestProperty("Content-Type", request.entity.contentType().value)
    }
  }

  private def sendRequestBody(
    connection: HttpURLConnection,
    request: HttpRequest
  ): Future[Unit] = {
    implicit val fm = ActorFlowMaterializer(namePrefix = Some("JavaUrlConnectionHttpClient"))

    if (request.method.isEntityAccepted) {
      request.entity match {
        case _ if request.entity.isKnownEmpty() =>
          connection.setFixedLengthStreamingMode(0)
        case _: HttpEntity.Chunked => {
          // Note requests may be rechunked by the Java client - i.e. they
          // may not be the same size that you might expect.
          // By calling flush in the loop below we should keep the same chunks
          // the source provides - if they're over 4 KiB they will
          // be broken up into smaller chunks.
          connection.setChunkedStreamingMode(0)
        }
        case entity: HttpEntity.Default =>
          connection.setFixedLengthStreamingMode(entity.contentLength.toInt)
        case entity: HttpEntity.Strict =>
          connection.setFixedLengthStreamingMode(entity.contentLength.toInt)
      }

      log.debug("calling connection.getOutputStream()")
      val outputStream = connection.getOutputStream()

      // Closing over the connection here should be thread safe as the
      // ForEachSink is materialized in a single actor.
      val requestSendingFuture = request.entity.dataBytes.runWith(Sink.foreach { bytes =>
        try {
          outputStream.write(bytes.toArray)
          outputStream.flush()
        } catch {
          case NonFatal(_) =>
            // TODO: Due to an Akka bug, the future will not be completed
            // if the function throws an exception (avoiding stream closure). While
            // can't reliably reproduce bugs with this, it is safer to protect from it.
            //
            // See https://github.com/akka/akka/issues/16866
        }
      })

      requestSendingFuture.onComplete {
        case _ => outputStream.close()
      }

      requestSendingFuture
    } else {

      Future.successful(())
    }
  }

  private def readResponse(
    connection: HttpURLConnection
  ): Future[HttpResponse] = {
    try {
      val responseFuture = readResponseBody(connection)

      responseFuture.map { case (code, bodyDataSource) =>
        val (responseHeaders, contentType, contentLength) = convertHeaders(connection)

        val entity = (code, contentLength) match {
          case (StatusCodes.NoContent.intValue, None | Some(0)) =>
            HttpEntity.Empty
          case (_, Some(len)) =>
            HttpEntity(contentType, len, bodyDataSource)
          case (_, None) =>
            HttpEntity.CloseDelimited(contentType, bodyDataSource)
        }

        new HttpResponse(StatusCodes.getForKey(code).get, responseHeaders, entity)
      }
    }
    catch {
      case NonFatal(e) => {
        Future.failed(e)
      }
    }
  }

  private def readResponseBody(
    connection: HttpURLConnection
  ): Future[(Int, Source[ByteString])] = {

    Future {
      log.debug("calling connection.getResponseCode()")
      val code = connection.getResponseCode()
      log.debug("got status code {}", code)

      // Confusingly in error cases (status >= 400) you have to use a
      // separate stream. See:
      // http://stackoverflow.com/questions/613307/read-error-response-body-in-java#comment16068076_613484
      val stream = if (code >= 400)
        Option(connection.getErrorStream())
      else
        Option(connection.getInputStream())

      (code, Source(Props(new ResponsePublisher(code, stream))))
    }
  }

  /**
   * Maps the headers to akka http types. Removes the Content-Type and Content-Length
   * headers and returns them separately.
   */
  private def convertHeaders(
    connection: HttpURLConnection
  ): (List[HttpHeader], ContentType, Option[Long]) = {

    case class HeaderAccumulator(headers: List[HttpHeader], cType: Option[ContentType], cLen: Option[Long])

    val rawHeaders = connection.getHeaderFields()
      .filterNot { case (k, _) => k == null }
      .flatMap { case (key, values) =>
        values.map(RawHeader(key, _))
      }.toList
    val (errors, parsedHeaders) = HeaderParser.parseHeaders(rawHeaders)
    errors.foreach { e =>
      log.warning(e.withSummaryPrepended(s"Failed to parse HTTP response header for request to ${connection.getURL}").formatPretty)
    }
    val HeaderAccumulator(filteredHeaders, contentType, contentLength) =
      parsedHeaders.foldRight(HeaderAccumulator(Nil, None, None)) { (header, acc) =>
        header match {
          case `Content-Type`(cType) => acc.copy(cType = Some(cType))
          case `Content-Length`(len) => acc.copy(cLen = Some(len))
          case _ => acc.copy(headers = header :: acc.headers)
        }
    }
    (filteredHeaders, contentType.getOrElse(ContentTypes.NoContentType), contentLength)
  }
}

object JavaUrlConnectionHttpClient {
  class ResponsePublisher(
      responseCode: Int,
      responseBody: Option[InputStream])
    extends ActorPublisher[ByteString]
    with ActorLogging {

    case object InternalDemand

    val buf = new Array[Byte](1024)

    override def receive: Receive = waitingForResponse

    // Start by sending the response data to 'self':
    self ! (responseCode, responseBody)

    def waitingForResponse: Receive = {
      case (_, Some(stream: InputStream)) if isCanceled =>
        stream.close()
      case (_, Some(stream: InputStream)) =>
        context.become(streamingResponse(stream))
        self ! InternalDemand
      case (401, None) =>
        // "None" for the stream usually means we've used the wrong stream (input vs. error).
        // However, if the response code was a 401 it can also be caused by this bug that we can't work around:
        // https://bugs.openjdk.java.net/browse/JDK-8052118?page=com.atlassian.jira.plugin.system.issuetabpanels:changehistory-tabpanel
        onComplete()
      case (_, None) =>
        onError(new Exception("Got a null input stream - from the HttpUrlConnection - probably using the incorrect stream"))
      case Status.Failure(ex) =>
        onError(ex)
      case _ => // Ignore demand for now.
    }

    def streamingResponse(stream: InputStream): Receive = {
      case ActorPublisherMessage.Request(_) | InternalDemand
          if isActive && totalDemand > 0 =>
        sendBytesIfAvailable(stream)
      case ActorPublisherMessage.Cancel =>
        stream.close()
    }

    private def sendBytesIfAvailable(stream: InputStream) {
      try {
        val bytesToRead = stream.available() match {
            case 0 =>
              // we need to read 1 byte even if the stream reports 0 available
              // because the API doesn't distinguish between no bytes being buffered
              // (but there are more to come) and reaching the end of the stream.
              // If we don't read at least one byte we may never know we've finished.
              1
            case n => n
          }
          val bytesRead = stream.read(buf, 0, math.min(buf.length, bytesToRead))
          bytesRead match {
            case -1 =>
              stream.close()
              onComplete()
            case 0 =>
              if (totalDemand > 0)
                self ! InternalDemand
            case _ =>
              onNext(ByteString.fromArray(buf, 0, bytesRead))
              if (totalDemand > 0)
                self ! InternalDemand
            }
      } catch {
        case ex: IOException =>
          onError(ex)
      }
    }
  }
}
