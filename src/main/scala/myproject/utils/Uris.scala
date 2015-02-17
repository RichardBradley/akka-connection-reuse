package myproject.utils

import akka.http.model.Uri

/**
 * Util methods for [[Uri]]s
 */
object Uris {

  implicit class UriWrapper(uri: Uri) {
    /**
     * Appends the given path to this Uri, returning a new Uri
     */
    def /(extraPath: Uri.Path): Uri = {
      uri.withPath(uri.path ++ extraPath)
    }

    /**
     * Appends the given path segment to this Uri, returning a new Uri
     */
    def /(extraPathSegment: String): Uri = {
      uri.withPath(uri.path / extraPathSegment)
    }
  }
}
