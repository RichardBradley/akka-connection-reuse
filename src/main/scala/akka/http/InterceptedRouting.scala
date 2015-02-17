package akka.http

import akka.http.model.headers.`Remote-Address`
import akka.http.model.{RemoteAddress, HttpResponse, HttpRequest}
import akka.http.server.{RouteResult, RequestContextImpl, RoutingSetup, Route}
import akka.stream.scaladsl.Flow
import myproject.RequestResponseMapper
import scala.concurrent.Future
import akka.http.util.FastFuture._

/**
 * A modified version of the [[akka.http.server.Route]] object, which provides an intercepted
 * flow for handling requests.
 *
 * It must be in the akka.http namespace as it uses [[RequestContextImpl]].
 *
 * We can intercept the Request and Response flows individually without this, but then we have
 * to correlate them manually.
 */
object InterceptedRouting {

  /**
   * Turns a [[Route]] into an server flow.
   */
  def interceptedHandlerFlow(
    route: Route,
    requestResponseMapper: RequestResponseMapper)(
    implicit setup: RoutingSetup
  ): Flow[HttpRequest, HttpResponse] =
    Flow[HttpRequest].mapAsync(asyncHandler(route, requestResponseMapper))

  /**
   * Turns a [[Route]] into an async handler function.
   */
  def asyncHandler(
    route: Route,
    requestResponseMapper: RequestResponseMapper)(
    implicit setup: RoutingSetup
  ): HttpRequest => Future[HttpResponse] = {
    import setup._
    val sealedRoute = Route.seal(route)
    request =>
      val boundResponseMapper = requestResponseMapper.mapRequest(addRemoteAddressHeader(request))

      val resultFuture = sealedRoute(new RequestContextImpl(
        boundResponseMapper.mappedRequest,
        routingLog.requestLog(request),
        setup.settings)).fast

      resultFuture.map {
        case RouteResult.Complete(response) =>
          boundResponseMapper.mapResponse(response)
        case RouteResult.Rejected(rejected) =>
          throw new IllegalStateException(s"Unhandled rejections '$rejected', unsealed RejectionHandler?!")
      }
  }

  /**
   * TODO: currently the "remote-address-header" setting doesn't work.
   * This is expected to be fixed in an upcoming version of Akka HTTP
   */
  private def addRemoteAddressHeader(request: HttpRequest): HttpRequest = {
    require(request.header[`Remote-Address`].isEmpty,
      "It looks like Akka have fixed remote-address-header; please delete this method")

    request.mapHeaders { `Remote-Address`(RemoteAddress("127.0.0.1")) +: _ }
  }
}
