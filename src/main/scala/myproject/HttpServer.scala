package myproject

import akka.http.InterceptedRouting.interceptedHandlerFlow
import akka.http.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.http.server.Route
import akka.http.{Http, InterceptedRouting}
import akka.stream.scaladsl.{Flow, MaterializedMap, Sink}
import akka.stream.stage.{Context, Directive, PushPullStage}
import akka.util.ByteString
import scala.concurrent.Promise
import scala.util.{Failure, Success}

/**
 * This is the base class for the main application HTTP server in a
 * myproject application.
 */
abstract class HttpServer(
    override val route: Route)
  extends HttpServerBase {

  override def port = 80 // from config, usually
  override def host = "0.0.0.0" // from config, usually
  override def socketBacklog = 1024 // from config, usually

  /**
   * Override the binding to allow the request pipeline interception
   */
  override def handleBinding(binding: Http.ServerBinding): MaterializedMap = {
    binding.startHandlingWith(interceptedHandlerFlow(route, requestResponseMapper))
  }

  /**
   * This method replaces all parseable headers in a request with strongly-typed headers
   */
  def parseHeaders(request: HttpRequest): HttpRequest = {
    // in our app, we parse custom headers here
    request
  }

  /**
   * Subclasses can make any changes necessary to the request at this point,
   * before any other processing is done.
   */
  def mapIncomingRequest(request: HttpRequest): HttpRequest = request

  /**
   * Subclasses can make any changes necessary to the response at this point,
   * after all other processing is done.
   */
  def mapOutgoingResponse(response: HttpResponse): HttpResponse = response

  /**
   * The mapper that [[InterceptedRouting]] uses to map the request and response.
   *
   * This creates and associates a [[RequestResponseMapper]] with the request and its response.
   */
  def requestResponseMapper: RequestResponseMapper = new RequestResponseMapper {

    override def mapRequest(request: HttpRequest): BoundRequestResponseMapper = {

      // TODO:https://github.com/akka/akka/issues/16893 all this materializedRequestStream stuff ought to be done by Akka, really

      // If the request body stream has been fully or partially read, this promise
      // will be completed with an instance of the stream stage added below.
      val materializedRequestStream =
        Promise[RequestStreamMaterliaizationMonitorStage]()

      def captureMaterializedSource(request: HttpRequest): HttpRequest = {
        request.withEntity(
          request.entity.transformDataBytes(
            Flow.empty.transform(
              () => {
                val stage = new RequestStreamMaterliaizationMonitorStage()
                materializedRequestStream.success(stage)
                stage
              })))
      }

      val processedRequest =
        captureMaterializedSource(
            parseHeaders(
              mapIncomingRequest(
                request)))

      new BoundRequestResponseMapper {

        override val mappedRequest = processedRequest

        // Subclasses may need to modify the response and we add this functionality here.
        override def mapResponse(response: HttpResponse) = {

          // To avoid qq, the request body must be either fully read or
          // cancelled.
          val response2 = materializedRequestStream.future.value match {
            case Some(Success(stream)) =>
              if (stream.isFinished) {
                // Nothing to do; the endpoint read the full request body
                response
              } else {
                // At this point, we really need to consume the rest of the stream.
                // However, the Akka stream implementation is very resistant to us
                // cancelling the stream from the outside.
                // TODO:https://github.com/akka/akka/issues/16893 What can we do here?
                val msg = "Internal error: the server is in an inconsistent state. " +
                  "The request body has only been part read, but the endpoint worker " +
                  "has stopped processing. You must fix the endpoint worker. The original " +
                  "response was:\n" + response

                // The response below bypasses the ExceptionHandler, so won't otherwise
                // get logged
                log.error(msg)

                HttpResponse(
                  StatusCodes.InternalServerError,
                  entity = msg)
              }
            case Some(Failure(e)) =>
              throw new Exception(e)
            case None =>
              // The request body was never read
              // We must consume the request.
              // TODO:https://github.com/akka/akka/issues/16893
              // using 'Sink.cancelled' doesn't work, likely due to a
              // bug in sun.net.www.protocol.http.HttpURLConnection
              request.entity.dataBytes.runWith(Sink.ignore)
              response
          }

          mapOutgoingResponse(super.mapResponse(response2))
        }
      }
    }

    private class RequestStreamMaterliaizationMonitorStage
      extends PushPullStage[ByteString, ByteString] {

      @volatile
      var isFinished = false

      override def onPush(elem: ByteString, ctx: Context[ByteString]): Directive = {
        ctx.push(elem)
      }

      override def onPull(ctx: Context[ByteString]): Directive = {
        ctx.pull()
      }

      override def onUpstreamFinish(ctx: Context[ByteString]) = {
        isFinished = true
        super.onUpstreamFinish(ctx)
      }

      override def onDownstreamFinish(ctx: Context[ByteString]) = {
        isFinished = true
        super.onDownstreamFinish(ctx)
      }

      override def onUpstreamFailure(cause: Throwable, ctx: Context[ByteString]) = {
        isFinished = true
        super.onUpstreamFailure(cause, ctx)
      }
    }
  }
}
