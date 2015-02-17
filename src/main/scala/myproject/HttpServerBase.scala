package myproject

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.http.Http
import akka.http.server.RouteResult._
import akka.http.server._
import akka.pattern.{after, pipe}
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.MaterializedMap
import java.net.InetSocketAddress
import myproject.HttpServerBase._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Provides a framework for setting up a local HTTP server.
 *
 * The actor will start in an idle state, and will reply to any
 * [[Bind]] messages with [[Bound]] or [[Status.Failure]]
 */
abstract class HttpServerBase
  extends Actor
  with ActorLogging
  with Directives {

  /**
   * The number of the port that this server should listen for requests on.
   *
   * A port of "0" means that the O/S should choose a free port.
   */
  def port: Int
  def host: String
  def socketBacklog: Int

  def getBindTimeout = 5.seconds

  implicit val system = context.system
  implicit val dispatcher = context.dispatcher
  implicit val materializer = ActorFlowMaterializer()

  /**
   * Override to add any additional receive behaviour that the server should have.
   */
  def additionalBehaviourOnceBound: Receive = PartialFunction.empty

  def route: Route

  def initialize(): Unit = {
    val binding = Http().bind(host, port, socketBacklog)
    val materializedMap = handleBinding(binding)
    val localAddress = binding.localAddress(materializedMap)

    val bindTimeout = system.scheduler.scheduleOnce(
      getBindTimeout,
      self,
      BindFailed(new Exception("Bind timed out after " + getBindTimeout)))

    // Store the unbind function to save passing round both the binding and the
    // Materialized Map.
    // TODO: Check what this does to open connections - we need it to
    // allow them to finish.
    val unbindFn = () => binding.unbind(materializedMap).map(_ => Unbound)

    localAddress.onComplete {
      case Success(address) =>
        bindTimeout.cancel()
        self ! BindSuccess(address, unbindFn)
      case Failure(e) =>
        self ! BindFailed(e)
    }
  }

  protected def handleBinding(binding: Http.ServerBinding): MaterializedMap =
    binding.startHandlingWith(
      route2HandlerFlow(route))

  override def receive: Receive = {
    case Bind =>
      initialize()
      context.become(awaitingBinding(Seq(sender())))
  }

  def awaitingBinding(currentlyWaitingInitializers: Seq[ActorRef]): Receive = {
    case b@ BindSuccess(address, unbindFn) =>
      log.info(s"Bound server '${getClass.getSimpleName}' to '$address'. Awaiting requests")
      currentlyWaitingInitializers.foreach(_ ! Bound(address))
      context.become(bound(b) orElse additionalBehaviourOnceBound)
    case BindFailed(e) =>
      log.error(e, s"Failed to bind server '${getClass.getSimpleName}'")

      val reply = Status.Failure(
        new Exception(s"Unable to bind to $host:$port", e))

      currentlyWaitingInitializers.foreach(_ ! reply)
      context.become {
        case Bind => sender ! reply
      }
    case Bind =>
      context.become(awaitingBinding(currentlyWaitingInitializers :+ sender()))
  }

  def bound(state: BindSuccess): Receive = {
    case Bind =>
      sender() ! Bound(state.address)
    case Unbind =>
      // TODO:(? no akka issue) this delay shouldn't be needed when Akka fix things
      // so that "unbind" waits for currently executing requests to complete
      val unbindF = after(1.second, system.scheduler) {
        state.unbindFn()
      }

      unbindF pipeTo sender()
      context.become {
        case Unbind => unbindF pipeTo sender()
      }
  }

  private case class BindFailed(e: Throwable)
  private case class BindSuccess(address: InetSocketAddress, unbindFn: () => Future[Unbound.type])
}

object HttpServerBase {

  /**
   * Send this to a [[HttpServerBase]] to request that it bind to the HTTP port.
   * It will reply with [[Bound]] or [[Status.Failure]]
   */
  case object Bind
  case class Bound(address: InetSocketAddress)

  /**
   * Send this to a [[HttpServerBase]] to request that it unbind from the HTTP port.
   * It will reply with [[Unbound]] or [[Status.Failure]].
   *
   * The operation will not complete until all open requests have finished.
   */
  case object Unbind
  case object Unbound
}
