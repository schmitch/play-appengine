package play.core.server.servlet

import java.nio.ByteBuffer
import javax.servlet.AsyncContext
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.reactivestreams.{ Publisher, Subscriber }
import org.reactivestreams.servlet.{ RequestPublisher, ResponseSubscriber }
import play.api.Logger

import scala.concurrent.duration.FiniteDuration

final class ServletRequestWrapper(
    val request: HttpServletRequest,
    val response: HttpServletResponse,
    val async: Option[ServletRequestWrapper.AsyncWrapper]
) {

  private val logger = Logger(this.getClass)

  private def createBodySource: Source[ByteString, Any] = {
    // currently this feeds the whole body into a byte array
    // if run under sync mode
    async match {
      case Some(value) =>
        Source.fromPublisher(value.requestPublisher).map(ByteString.fromByteBuffer)
      case None =>
        val inputStream = request.getInputStream
        val output      = new java.io.ByteArrayOutputStream()
        val buffer      = new Array[Byte](1024 * 8)
        var length      = inputStream.read(buffer)
        while (length != -1) {
          output.write(buffer, 0, length)
          length = inputStream.read(buffer)
        }
        Source.single(ByteString(output.toByteArray))
    }
  }

  /** keep a val here since we actually need to materialize it as soon as we create the wrapper */
  val bodySource: Option[Source[ByteString, Any]] = {
    if (ServletUtil.hasBody(request)) {
      logger.trace(s"Request hasBody: ${request.isAsyncSupported}")
      Some(createBodySource)
    } else {
      None
    }
  }

}

object ServletRequestWrapper {

  final class AsyncWrapper(
      val context: AsyncContext,
      val requestPublisher: Publisher[ByteBuffer],
      val requestSubscriber: Subscriber[ByteBuffer],
  )

  def apply(
      request: HttpServletRequest,
      response: HttpServletResponse,
      bufferSize: Int = 8192,
      asyncTimeout: Option[FiniteDuration] = None
  ): ServletRequestWrapper = {
    val asyncWrapper = {
      if (request.isAsyncSupported) {
        val context = if (request.isAsyncStarted) request.startAsync() else request.getAsyncContext

        asyncTimeout.foreach { duration =>
          context.setTimeout(duration.toMillis)
        }

        val publisher  = new RequestPublisher(context, bufferSize)
        val subscriber = new ResponseSubscriber(context)
        Some(new AsyncWrapper(context, publisher, subscriber))
      } else {
        None
      }
    }

    new ServletRequestWrapper(request, response, asyncWrapper)
  }

}
