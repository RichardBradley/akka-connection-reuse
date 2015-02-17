// (C) COPYRIGHT METASWITCH NETWORKS 2014
package myproject.utils

import akka.http.model.HttpEntity

/**
 * Util functions for [[HttpEntity]]
 */
object HttpEntities {

  /**
   * Converting an HttpEntity to a string with the correct charset is a little
   * tricky.
   *
   * Hopefully future versions of the Akka API will make this obsolete.
   */
  implicit class StrictEntityWithAsString(entity: HttpEntity.Strict) {

    def asString(): String = {
      entity.data.decodeString(entity.contentType.charset.nioCharset.toString())
    }
  }
}
