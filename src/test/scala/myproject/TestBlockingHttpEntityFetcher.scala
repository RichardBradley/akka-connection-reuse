package myproject

import akka.actor.ActorRefFactory
import akka.http.model.HttpEntity
import akka.stream.ActorFlowMaterializer
import myproject.utils.HttpEntities._
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * A helper to block until an HttpEntity arrives.
 * For test use only.
 */
object TestBlockingHttpEntityFetcher {

  def fetchEntity(
    entity: HttpEntity)(
    implicit context: ActorRefFactory
  ): HttpEntity.Strict = {
    def streamIn(): HttpEntity.Strict = {
      val entityF = entity.toStrict(10.seconds)(
        ActorFlowMaterializer(namePrefix = Some("TestBlockingHttpEntityFetcher")))

      Await.result(entityF, 10.seconds)
    }

    entity match {
      case HttpEntity.Default(ctype, clength, data) =>
        val strict = streamIn()
        // Check for already-streamed entities
        // See https://github.com/akka/akka/issues/15835 -- Akka silently fails here currently
        require(strict.data.length == clength,
          "Error streaming entity: perhaps this stream was already consumed? " +
            "Each entity can be streamed only once.")
        strict
      case strict: HttpEntity.Strict =>
        strict
      case _ =>
        // Hard to check other types (Chunked, CloseDelimited, IndefiniteLength) here
        streamIn()
    }
  }

  implicit class EntityWithBlockingAsString(
      entity: HttpEntity)(
      implicit context: ActorRefFactory) {

    def asString(): String = {
      val strictEntity = fetchEntity(entity)
      StrictEntityWithAsString(strictEntity).asString()
    }
  }
}
