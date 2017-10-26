package play.core.server.servlet

import java.net.{ InetAddress, InetSocketAddress, URI }
import java.security.cert.X509Certificate
import java.time.Instant
import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import akka.stream.{ IOResult, Materializer }
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.Logger
import play.api.http.{ HeaderNames, HttpChunk, HttpEntity, HttpErrorHandler }
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{ RemoteConnection, RequestAttrKey, RequestTarget }
import play.api.mvc.{ RequestHeader, RequestHeaderImpl, ResponseHeader, Result }
import play.core.server.common.{ ForwardedHeaderHandler, ServerResultUtils }
import play.mvc.Http.Status
import play.api.http.HeaderNames._
import play.core.server.servlet.stream.{ AkkaStreamReadListener, AkkaStreamWriteListener }

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.util.Try

class ServletModelConversion(
    resultUtils: ServerResultUtils,
    forwardedHeaderHandler: ForwardedHeaderHandler,
) {

  private val logger = Logger(getClass)
  private val CRLF   = ByteString.fromString("\r\n")

  private def parseUriAndPathAndQuery(uri: String): (String, String, String) = {
    // https://tools.ietf.org/html/rfc3986#section-3.3
    val withoutHost = uri.dropWhile(_ != '/')
    // The path is terminated by the first question mark ("?")
    // or number sign ("#") character, or by the end of the URI.
    val queryEndPos = Some(withoutHost.indexOf('#')).filter(_ != -1).getOrElse(withoutHost.length)
    val pathEndPos  = Some(withoutHost.indexOf('?')).filter(_ != -1).getOrElse(queryEndPos)
    val path        = withoutHost.substring(0, pathEndPos)
    // https://tools.ietf.org/html/rfc3986#section-3.4
    // The query component is indicated by the first question
    // mark ("?") character and terminated by a number sign ("#") character
    // or by the end of the URI.
    val queryString = withoutHost.substring(pathEndPos, queryEndPos)
    (withoutHost, path, queryString)
  }

  /**
   * Convert a Servlet request to a Play RequestHeader.
   *
   * Will return a failure if there's a protocol error or some other error in the header.
   */
  def convertRequest(request: HttpServletRequest): Try[RequestHeader] = {
    Try {
      val target: RequestTarget = createRequestTarget(request)
      createRequestHeader(request, target)
    }
  }

  /** Capture a request's connection info from its channel and headers. */
  private def createRemoteConnection(request: HttpServletRequest, headers: ServletHeadersWrapper): RemoteConnection = {
    val rawConnection = new RemoteConnection {
      override lazy val remoteAddress: InetAddress = {
        InetSocketAddress.createUnresolved(request.getRemoteHost, request.getRemotePort).getAddress
      }
      override def secure: Boolean                                           = request.isSecure
      override lazy val clientCertificateChain: Option[Seq[X509Certificate]] = None
    }
    forwardedHeaderHandler.forwardedConnection(rawConnection, headers)
  }

  /** Create request target information from a Netty request. */
  private def createRequestTarget(request: HttpServletRequest): RequestTarget = {
    val (baseUri, unsafePath, parsedQueryString) = parseUriAndPathAndQuery(request.getRequestURI)
    // wrapping into URI to handle absoluteURI and path validation
    val parsedPath = Option(new URI(unsafePath).getRawPath).getOrElse {
      // if the URI has a invalid path, this will trigger a 400 error
      throw new IllegalStateException(s"Cannot parse path from URI: $unsafePath")
    }
    new RequestTarget {
      override lazy val uri: URI       = new URI(uriString)
      override def uriString: String   = baseUri
      override val path: String        = parsedPath
      override val queryString: String = parsedQueryString.stripPrefix("?")
      override lazy val queryMap: Map[String, Seq[String]] = {
        val decoder           = new QueryStringDecoder(parsedQueryString)
        val decodedParameters = decoder.parameters()
        if (decodedParameters.isEmpty) Map.empty
        else decodedParameters.asScala.mapValues(_.asScala.toList).toMap
      }
    }
  }

  /**
   * Create request target information from a Netty request where
   * there was a parsing failure.
   */
  def createUnparsedRequestTarget(request: HttpServletRequest): RequestTarget = new RequestTarget {
    override lazy val uri: URI     = new URI(uriString)
    override def uriString: String = request.getRequestURI
    override lazy val path: String = {
      // The URI may be invalid, so instead, do a crude heuristic to drop the host and query string from it to get the
      // path, and don't decode.
      // RICH: This looks like a source of potential security bugs to me!
      val withoutHost        = uriString.dropWhile(_ != '/')
      val withoutQueryString = withoutHost.split('?').head
      if (withoutQueryString.isEmpty) "/" else withoutQueryString
    }
    override lazy val queryMap: Map[String, Seq[String]] = {
      // Very rough parse of query string that doesn't decode
      if (request.getRequestURI.contains("?")) {
        request.getRequestURI
          .split("\\?", 2)(1)
          .split('&')
          .map { keyPair =>
            keyPair.split("=", 2) match {
              case Array(key)        => key -> ""
              case Array(key, value) => key -> value
            }
          }
          .groupBy(_._1)
          .map {
            case (name, values) => name -> values.map(_._2).toSeq
          }
      } else {
        Map.empty
      }
    }
  }

  /**
   * Convert the request headers of an Akka `HttpRequest` to a Play `Headers` object.
   */
  private def convertRequestHeadersServlet(request: HttpServletRequest): ServletHeadersWrapper = {
    val knownContentLength: Option[String] = Some(request.getContentLengthLong).filter(_ != -1).map(_.toString)
    val isChunked: Option[String]          = Option(request.getHeader(HeaderNames.TRANSFER_ENCODING))

    val requestUri = request.getRequestURI

    // converts request headers to a Play Format
    val hs = {
      request.getHeaderNames.asScala
        .filter { headerName =>
          headerName.equalsIgnoreCase(HeaderNames.CONTENT_TYPE) &&
          headerName.equalsIgnoreCase(HeaderNames.TRANSFER_ENCODING)
        }
        .flatMap { headerName =>
          request.getHeaders(headerName).asScala.map(headerValue => headerName -> headerValue)
        }
        .toSeq
    }

    new ServletHeadersWrapper(request, knownContentLength, hs, isChunked, requestUri)
  }

  /**
   * Create the request header. This header is not created with the application's
   * RequestFactory, simply because we don't yet have an application at this phase
   * of request processing. We'll pass it through the application's RequestFactory
   * later.
   */
  def createRequestHeader(request: HttpServletRequest, target: RequestTarget): RequestHeader = {
    val headers = convertRequestHeadersServlet(request)
    new RequestHeaderImpl(
      createRemoteConnection(request, headers),
      request.getMethod,
      target,
      request.getProtocol,
      headers,
      // Send an attribute so our tests can tell which kind of server we're using.
      // We only do this for the "non-default" engine, so we used to tag
      // akka-http explicitly, so that benchmarking isn't affected by this.
      TypedMap(RequestAttrKey.Server -> "servlet")
    )
  }

  /** Create the source for the request body */
  def convertRequestBody(request: HttpServletRequest)(implicit mat: Materializer): Option[Source[ByteString, Any]] = {
    // FIXME: try to read in a more blocking/compact fashion if body <= 4K
    if (ServletUtil.hasBody(request)) {
      Some(AkkaStreamReadListener.fromServletInputStream(request.getInputStream))
    } else {
      None
    }
  }

  /** Create a Netty response from the result */
  def convertResult(
      result: Result,
      requestHeader: RequestHeader,
      protocol: String,
      errorHandler: HttpErrorHandler,
      response: HttpServletResponse
  )(implicit mat: Materializer): Future[IOResult] = {

    resultUtils.resultConversionWithErrorHandling(requestHeader, result, errorHandler) { result =>
      result.header.reasonPhrase match {
        case Some(phrase) => response.sendError(result.header.status, phrase)
        case None         => response.setStatus(result.header.status)
      }

      val connectionHeader = resultUtils.determineConnectionHeader(requestHeader, result)
      val skipEntity       = requestHeader.method == "HEAD"

      val future = result.body match {
        case any if skipEntity =>
          resultUtils.cancelEntity(any)
          Future.successful(IOResult.createSuccessful(0))

        // FIXME: faster strict values?
        case HttpEntity.Strict(data, _) =>
          createStreamedResponse(Source.single(data), response)

        case HttpEntity.Streamed(stream, _, _) =>
          createStreamedResponse(stream, response)

        case HttpEntity.Chunked(chunks, _) =>
          createChunkedResponse(chunks, response)
      }

      // Set response headers
      val headers = resultUtils.splitSetCookieHeaders(result.header.headers)
      headers foreach {
        case (name, value) => response.addHeader(name, value)
      }

      // Content type and length
      if (resultUtils.mayHaveEntity(result.header.status)) {
        result.body.contentLength.foreach { contentLength =>
          if (ServletUtil.isContentLengthSet(response)) {
            val manualContentLength = response.getHeader(CONTENT_LENGTH)
            if (manualContentLength == contentLength.toString) {
              logger.info(s"Manual Content-Length header, ignoring manual header.")
            } else {
              logger.warn(
                s"Content-Length header was set manually in the header ($manualContentLength) but is not the same as actual content length ($contentLength)."
              )
            }
          }
          ServletUtil.setContentLength(response, contentLength)
        }
      } else if (ServletUtil.isContentLengthSet(response)) {
        val manualContentLength = response.getHeader(CONTENT_LENGTH)
        logger.warn(
          s"Ignoring manual Content-Length ($manualContentLength) since it is not allowed for ${result.header.status} responses."
        )
        // FIXME: does this work?, we might need to exclude it before
        response.setHeader(CONTENT_LENGTH, null)
      }
      val responseHeaders = response.getHeaderNames.asScala.toList
      result.body.contentType.foreach { contentType =>
        if (responseHeaders.contains(CONTENT_TYPE)) {
          logger.warn(
            s"Content-Type set both in header (${response.getHeader(CONTENT_TYPE)}) and attached to entity ($contentType), ignoring content type from entity. To remove this warning, use Result.as(...) to set the content type, rather than setting the header manually."
          )
        } else {
          response.addHeader(CONTENT_TYPE, contentType)
        }
      }

      connectionHeader.header.foreach { headerValue =>
        response.setHeader(CONNECTION, headerValue)
      }

      // Netty doesn't add the required Date header for us, so make sure there is one here
      if (!responseHeaders.contains(DATE)) {
        response.addHeader(DATE, dateHeader)
      }

      future
    } {
      // Fallback response
      response.setStatus(Status.INTERNAL_SERVER_ERROR)
      response.setContentLength(0)
      response.addHeader(DATE, dateHeader)
      response.addHeader(CONNECTION, "close")
      IOResult.createSuccessful(0)
    }
  }

  private def createStreamedResponse(
      stream: Source[ByteString, _],
      response: HttpServletResponse
  )(implicit mat: Materializer) = {
    val outputStream = response.getOutputStream
    val listener = stream.runWith(AkkaStreamWriteListener.fromServletOutputStream(outputStream))
    outputStream.setWriteListener(listener)
    listener.waitUntilFinished
  }

  /** Create a servlet chunked response. */
  private def createChunkedResponse(
      chunks: Source[HttpChunk, _],
      response: HttpServletResponse,
  )(implicit mat: Materializer) = {

    response.addHeader(TRANSFER_ENCODING, "chunked")

    val stream = chunks
      .map {
        case HttpChunk.Chunk(bytes) =>
          ByteString
            .fromString(Integer.toHexString(bytes.length))
            .concat(CRLF)
            .concat(bytes)
            .concat(CRLF)
        case HttpChunk.LastChunk(trailers) =>
          trailers.toMap.foldLeft(ByteString.empty) {
            case (main, (name, values)) =>
              val header = ByteString.fromString(name)

              main ++ values
                .map(value => header.concat(ByteString.fromString(value)).concat(CRLF))
                .reduceLeft((b1, b2) => b1 ++ b2)
          }
      }

    createStreamedResponse(stream, response)
  }

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
}
