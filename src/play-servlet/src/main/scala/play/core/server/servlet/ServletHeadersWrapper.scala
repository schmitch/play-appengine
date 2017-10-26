package play.core.server.servlet

import java.util.Locale
import javax.servlet.http.HttpServletRequest

import play.api.http.HeaderNames
import play.api.mvc.Headers

final case class ServletHeadersWrapper(
    request: HttpServletRequest,
    knownContentLength: Option[String],
    hs: Seq[(String, String)],
    isChunked: Option[String],
    uri: String,
) extends Headers(null) {

  import ServletHeadersWrapper._

  private lazy val contentType = request.getContentType

  override lazy val headers: Seq[(String, String)] = {

    val h0 = (HeaderNames.CONTENT_TYPE -> contentType) +: hs
    val h1 = knownContentLength match {
      case Some(cl) => (HeaderNames.CONTENT_LENGTH -> cl) +: h0
      case _        => h0
    }
    val h2 = isChunked match {
      case Some(ch) => (HeaderNames.TRANSFER_ENCODING -> ch) +: h1
      case _        => h1
    }
    h2
  }

  override def hasHeader(headerName: String): Boolean = {
    headerName.toLowerCase(Locale.ROOT) match {
      case CONTENT_LENGTH_LOWER_CASE    => knownContentLength.isDefined
      case TRANSFER_ENCODING_LOWER_CASE => isChunked.isDefined
      case CONTENT_TYPE_LOWER_CASE      => true
      case _                            => get(headerName).isDefined
    }
  }

  override def hasBody: Boolean = {
    // FIXME: this might be incorrect
    knownContentLength.exists(_ != "0") || isChunked.isDefined
  }

  override def apply(key: String): String = {
    get(key).getOrElse(throw new RuntimeException(s"Header with name $key not found!"))
  }

  override def get(key: String): Option[String] = {
    key.toLowerCase(Locale.ROOT) match {
      case CONTENT_LENGTH_LOWER_CASE    => knownContentLength
      case TRANSFER_ENCODING_LOWER_CASE => isChunked
      case CONTENT_TYPE_LOWER_CASE      => Some(contentType)
      case lowerCased                   => hs.collectFirst { case h if h._1.equalsIgnoreCase(lowerCased) => h._2 }
    }
  }

  override def getAll(key: String): Seq[String] = {
    key.toLowerCase(Locale.ROOT) match {
      case CONTENT_LENGTH_LOWER_CASE    => knownContentLength.toList
      case TRANSFER_ENCODING_LOWER_CASE => isChunked.toList
      case CONTENT_TYPE_LOWER_CASE      => contentType :: Nil
      case lowerCased                   => hs.collect { case h if h._1.equalsIgnoreCase(lowerCased) => h._2 }
    }
  }

  override lazy val keys: Set[String] = {
    hs.map(_._1).toSet ++
    Set(CONTENT_LENGTH_LOWER_CASE, TRANSFER_ENCODING_LOWER_CASE, CONTENT_TYPE_LOWER_CASE).filter(hasHeader)
  }

  // note that these are rarely used, mostly just in tests
  override def add(headers: (String, String)*): ServletHeadersWrapper = {
    copy(hs = this.hs ++ raw(headers))
  }

  override def remove(keys: String*): Headers = {
    val filteredHeaders = hs.filterNot { h =>
      keys.exists { rm =>
        h._1.equalsIgnoreCase(rm.toLowerCase(Locale.ROOT))
      }
    }
    copy(hs = filteredHeaders)
  }

  override def replace(headers: (String, String)*): Headers =
    remove(headers.map(_._1): _*).add(headers: _*)

  override def equals(other: Any): Boolean =
    other match {
      case that: ServletHeadersWrapper => that.request == this.request
      case _                           => false
    }

  private def raw(headers: Seq[(String, String)]): Seq[(String, String)] = {
    headers
  }

  override def hashCode: Int = request.hashCode()

}

object ServletHeadersWrapper {
  val CONTENT_LENGTH_LOWER_CASE    = "content-length"
  val CONTENT_TYPE_LOWER_CASE      = "content-type"
  val TRANSFER_ENCODING_LOWER_CASE = "transfer-encoding"
}
