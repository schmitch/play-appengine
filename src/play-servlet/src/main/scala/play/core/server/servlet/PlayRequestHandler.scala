package play.core.server.servlet

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import akka.stream.{ IOResult, Materializer }
import play.api.http.{ DefaultHttpErrorHandler, HeaderNames, HttpErrorHandler, Status }
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.api.{ Application, Logger }
import play.core.server.PlayServlet
import play.core.server.common.{ ReloadCache, ServerResultUtils }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import scala.util.{ Failure, Success, Try }

final class PlayRequestHandler(servlet: PlayServlet) {

  private val logger: Logger = Logger(this.getClass)

  /**
   * Values that are cached based on the current application.
   */
  private case class ReloadCacheValues(
      resultUtils: ServerResultUtils,
      modelConversion: ServletModelConversion
  )

  /**
   * A helper to cache values that are derived from the current application.
   */
  private val reloadCache = new ReloadCache[ReloadCacheValues] {
    override protected def reloadValue(tryApp: Try[Application]): ReloadCacheValues = {
      val serverResultUtils      = reloadServerResultUtils(tryApp)
      val forwardedHeaderHandler = reloadForwardedHeaderHandler(tryApp)
      val modelConversion        = new ServletModelConversion(serverResultUtils, forwardedHeaderHandler)
      ReloadCacheValues(
        resultUtils = serverResultUtils,
        modelConversion = modelConversion
      )
    }
  }

  private def resultUtils: ServerResultUtils = {
    reloadCache.cachedFrom(servlet.applicationProvider.get).resultUtils
  }
  private def modelConversion: ServletModelConversion = {
    reloadCache.cachedFrom(servlet.applicationProvider.get).modelConversion
  }

  def handle(request: HttpServletRequest, response: HttpServletResponse): Unit = {
    import play.core.Execution.Implicits.trampoline
    logger.trace(s"Http Request received by servlet: ${request.getPathInfo}")
    logger.trace(s"Http Request supports Async: ${request.isAsyncSupported}")
    val wrapper = ServletRequestWrapper(request, response)

    val tryRequest: Try[RequestHeader] = modelConversion.convertRequest(wrapper)

    def clientError(statusCode: Int, message: String) = {
      val unparsedTarget = modelConversion.createUnparsedRequestTarget(request)
      val requestHeader  = modelConversion.createRequestHeader(wrapper, unparsedTarget)
      val result = errorHandler(servlet.applicationProvider.current)
        .onClientError(requestHeader, statusCode, if (message == null) "" else message)
      // If there's a problem in parsing the request, then we should close the connection, once done with it
      requestHeader -> Left(result.map(_.withHeaders(HeaderNames.CONNECTION -> "close")))
    }

    val (requestHeader, resultOrHandler) = tryRequest match {
      case Failure(exception) => clientError(Status.BAD_REQUEST, exception.getMessage)
      case Success(untagged) =>
        servlet.getHandlerFor(untagged) match {
          case Left(directResult) => untagged -> Left(directResult)
          case Right((taggedRequestHeader, handler, application)) =>
            taggedRequestHeader -> Right((handler, application))
        }
    }

    val awaitable = resultOrHandler match {
      // execute normal action
      case Right((action: EssentialAction, app)) =>
        val recovered = EssentialAction { rh =>
          action(rh).recoverWith {
            case error => app.errorHandler.onServerError(rh, error)
          }
        }
        logger.trace("Handler Found call handleAction")
        handleAction(recovered, requestHeader, wrapper, Some(app))

      // FIXME: unsuported
      //handle websocket request, which we do not support
      case Right((ws: WebSocket, app)) =>
        logger.trace("Bad websocket request")
        val action = EssentialAction(_ => Accumulator.done(Results.Status(Status.BAD_REQUEST)))
        handleAction(action, requestHeader, wrapper, Some(app))

      // This case usually indicates an error in Play's internal routing or handling logic
      case Right((h, _)) =>
        val ex = new IllegalStateException(s"Servlet server doesn't handle Handlers of this type: $h")
        logger.error(ex.getMessage, ex)
        throw ex

      case Left(e) =>
        logger.trace("No handler, got direct result: " + e)
        val action = EssentialAction(_ => Accumulator.done(e))
        handleAction(action, requestHeader, wrapper, None)
    }

    implicit val mat: Materializer = servlet.materializer
    import play.core.Execution.Implicits.trampoline
    wrapper.async match {
      case Some(_) =>
        awaitable
          .map { wrapper =>
            wrapper.writeResponseHeader()
            wrapper.writeResponseBody()
          }
          .onComplete {
            // complete the context, no matter if we have an error or not
            case Success(_) =>
              logger.trace("Request completed")
              wrapper.async match {
                case Some(_) =>
                case None    => response.flushBuffer()
              }
            case Failure(t) =>
              logger.trace("Request completed with failure", t)
              sendSimpleErrorResponse(Status.SERVICE_UNAVAILABLE, response)
              wrapper.async match {
                case Some(_) =>
                case None    => response.flushBuffer()
              }
          }
      case None =>
        // currently we await 30 seconds for sync request until
        // we cancel them and return a error response
        val responeWithMaterializedBody =
          awaitable.flatMap(response => response.writeResponseBodySync().map((response, _)))

        response.setBufferSize(8192) // FIXME: make bufferSize and timeout configurable
        Try(Await.result(responeWithMaterializedBody, 30.seconds)) match {
          case Success((resultResponse, body)) =>
            resultResponse.writeResponseHeader()
            body.foreach { responseBody =>
              val os = response.getOutputStream
              logger.trace(s"Try Writing Body")
              Try(os.write(responseBody)) match {
                case Success(_) => logger.trace("Body written")
                case Failure(t) => logger.trace("Body could not be written")
              }
              os.flush()
              os.close()
            }
            response.flushBuffer()
          case Failure(t) =>
            logger.trace("Request completed with failure", t)
            sendSimpleErrorResponse(Status.SERVICE_UNAVAILABLE, response)
            response.flushBuffer()
        }
    }
  }

  //----------------------------------------------------------------
  // Private methods

  /**
   * Handle an essential action.
   */
  private def handleAction(
      action: EssentialAction,
      requestHeader: RequestHeader,
      wrapper: ServletRequestWrapper,
      app: Option[Application],
  ): Future[ServletResponseWrapper] = {
    implicit val mat: Materializer = app.fold(servlet.materializer)(_.materializer)
    import play.core.Execution.Implicits.trampoline

    for {
      bodyParser <- Future(action(requestHeader))(mat.executionContext)
      actionResult <- {
        val bodyFuture = modelConversion.convertRequestBody(wrapper) match {
          case None         => bodyParser.run()
          case Some(source) => bodyParser.run(source)
        }
        bodyFuture.recoverWith {
          case error =>
            logger.error("Cannot invoke the action", error)
            errorHandler(app).onServerError(requestHeader, error)
        }
      }

      // Clean and validate the action's result
      validatedResult <- {
        val cleanedResult = resultUtils.prepareCookies(requestHeader, actionResult)
        resultUtils.validateResult(requestHeader, cleanedResult, errorHandler(app))
      }
      // Convert the result to a Netty HttpResponse
      convertedResult <- {
        modelConversion.convertResult(
          validatedResult,
          requestHeader,
          wrapper,
          errorHandler(app)
        )
      }
    } yield convertedResult
  }

  /**
   * Get the error handler for the application.
   */
  private def errorHandler(app: Option[Application]): HttpErrorHandler = {
    app.fold[HttpErrorHandler](DefaultHttpErrorHandler)(_.errorHandler)
  }

  /**
   * Sends a simple response with no body, then closes the connection.
   */
  private def sendSimpleErrorResponse(status: Int, response: HttpServletResponse): Unit = {
    response.addHeader(HeaderNames.CONNECTION, "close")
    response.setContentLength(0)
  }

}
