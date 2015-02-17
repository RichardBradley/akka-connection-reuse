package myproject

import akka.http.model.{HttpRequest, HttpResponse}

/**
 * A trait for mapping a [[HttpRequest]] and [[HttpResponse]] pair.
 *
 * It can take a request, and produce a [[BoundRequestResponseMapper]] for mapping the response.
 */
trait RequestResponseMapper {
  def mapRequest(request: HttpRequest): BoundRequestResponseMapper
}

/**
 * A trait for mapping the response to a given request.
 */
trait BoundRequestResponseMapper {
  val mappedRequest: HttpRequest

  def mapResponse(response: HttpResponse): HttpResponse = response
}
