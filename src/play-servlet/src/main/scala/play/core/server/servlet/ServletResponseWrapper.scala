package play.core.server.servlet

import java.time.Instant

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import play.api.mvc.ResponseHeader
import play.api.http.HeaderNames._

import scala.concurrent.Future

final class ServletResponseWrapper(
    status: Int,
    contentLength: Option[Long],
    contentType: Option[String],
    reasonPhrase: Option[String],
    headers: List[(String, String)],
    body: Option[Source[ByteString, _]],
    transferEncodingChunked: Boolean,
    connectionHeader: Option[String],
    request: ServletRequestWrapper,
    hasDate: Boolean = false
) {

  // cache the date header of the last response so we only need to compute it every second
  private var cachedDateHeader: (Long, String) = (Long.MinValue, null)
  private def dateHeader: String = {
    val currentTimeMillis  = System.currentTimeMillis()
    val currentTimeSeconds = currentTimeMillis / 1000
    cachedDateHeader match {
      case (cachedSeconds, dateHeaderString) if cachedSeconds == currentTimeSeconds =>
        dateHeaderString
      case _ =>
        val dateHeaderString = ResponseHeader.httpDateFormat.format(Instant.ofEpochMilli(currentTimeMillis))
        cachedDateHeader = currentTimeSeconds -> dateHeaderString
        dateHeaderString
    }
  }

  def writeResponseHeader(): Unit = {
    val response = request.response

    reasonPhrase match {
      case Some(reason) => response.sendError(status, reason)
      case None         => response.setStatus(status)
    }

    contentLength match {
      case Some(value) if !transferEncodingChunked =>
        response.setContentLengthLong(value)
      case _ =>
        response.setContentLengthLong(-1L)
        response.setHeader(TRANSFER_ENCODING, "chunked")
    }

    contentType match {
      case Some(ct) => response.setContentType(ct)
      case None =>
    }

    headers.foreach {
      case (key, value) =>
        response.addHeader(key, value)
    }

    if (!hasDate) {
      response.addHeader(DATE, dateHeader)
    }

    connectionHeader.foreach { headerValue =>
      response.setHeader(CONNECTION, headerValue)
    }
  }

  def writeResponseBody()(implicit materializer: Materializer): Unit = {
    request.async match {
      case Some(context) =>
        body
          .map { source =>
            source.map(_.toByteBuffer).runWith(Sink.fromSubscriber(context.requestSubscriber))
          }
          .getOrElse {
            context.context.complete()
          }
      case None =>
        throw new IllegalStateException("this method should never be called in a sync context")
    }
  }

  def writeResponseBodySync()(implicit materializer: Materializer): Future[Option[Array[Byte]]] = {
    import play.core.Execution.Implicits.trampoline

    body
      .map { source =>
        source.runWith(Sink.fold(ByteString.empty)(_ ++ _)).map(value => Some(value.toArray[Byte]))
      }
      .getOrElse {
        Future.successful(None)
      }
  }

}
