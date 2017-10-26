package play.core.server.servlet

import play.api.http.HeaderNames._
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

object ServletUtil {

  def hasBody(request: HttpServletRequest): Boolean = {
    request.getContentLengthLong != 0
  }

  def isContentLengthSet(response: HttpServletResponse): Boolean = {
    Option(response.getHeader(CONTENT_LENGTH)).isDefined
  }

  def setContentLength(response: HttpServletResponse, contentLength: Long): Unit = {
    response.setContentLengthLong(contentLength)
  }

}
