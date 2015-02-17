package myproject

import akka.actor.ActorRef
import akka.http.server._

trait RoutingService {

  def route(server: ActorRef): Route
}
