package play.core.server.servlet

import play.api.http.HeaderNames._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import scala.collection.JavaConverters._

object ServletUtil {

  def hasBody(request: HttpServletRequest): Boolean = {
    request.getContentLengthLong > 0 || (request.getContentLengthLong == -1L && request.getHeaders("Transfer-Encoding").asScala.contains("chunked"))
  }

  def isContentLengthSet(response: HttpServletResponse): Boolean = {
    Option(response.getHeader(CONTENT_LENGTH)).isDefined
  }

  def setContentLength(response: HttpServletResponse, contentLength: Long): Unit = {
    response.setContentLengthLong(contentLength)
  }

}
