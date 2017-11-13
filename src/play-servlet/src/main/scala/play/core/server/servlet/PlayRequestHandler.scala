package play.core.server.servlet

import javax.servlet.http.{ HttpServletRequest, HttpServletResponse }

import akka.stream.{ IOResult, Materializer }
import akka.util.ByteString
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

  private val logger: Logger = Logger(classOf[PlayServlet])

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

    val asyncContext = {
      if (request.isAsyncStarted) Some(request.startAsync())
      else None
    }

    // FIXME: set request timeout
    // asyncContext.setTimeout()

    val tryRequest: Try[RequestHeader] = modelConversion.convertRequest(request)

    def clientError(statusCode: Int, message: String) = {
      val unparsedTarget = modelConversion.createUnparsedRequestTarget(request)
      val requestHeader  = modelConversion.createRequestHeader(request, unparsedTarget)
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

    (resultOrHandler match {
      // execute normal action
      case Right((action: EssentialAction, app)) =>
        val recovered = EssentialAction { rh =>
          action(rh).recoverWith {
            case error => app.errorHandler.onServerError(rh, error)
          }
        }
        handleAction(recovered, requestHeader, request, Some(app), response)

      // FIXME: unsuported
      //handle websocket request, which we do not support
      case Right((ws: WebSocket, app)) =>
        logger.trace("Bad websocket request")
        val action = EssentialAction(_ => Accumulator.done(Results.Status(Status.BAD_REQUEST)))
        handleAction(action, requestHeader, request, Some(app), response)

      // This case usually indicates an error in Play's internal routing or handling logic
      case Right((h, _)) =>
        val ex = new IllegalStateException(s"Servlet server doesn't handle Handlers of this type: $h")
        logger.error(ex.getMessage, ex)
        throw ex

      case Left(e) =>
        logger.trace("No handler, got direct result: " + e)
        val action = EssentialAction(_ => Accumulator.done(e))
        handleAction(action, requestHeader, request, None, response)
    }).onComplete {
      // complete the context, no matter if we have an error or not
      case Success(_) =>
        logger.trace("Request completed")
        asyncContext match {
          case Some(context) => context.complete()
          case None          => response.flushBuffer()
        }
      case Failure(_) =>
        logger.trace("Request completed with failure")
        sendSimpleErrorResponse(Status.SERVICE_UNAVAILABLE, response)
        asyncContext match {
          case Some(context) => context.complete()
          case None          => response.flushBuffer()
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
      request: HttpServletRequest,
      app: Option[Application],
      response: HttpServletResponse
  ): Future[IOResult] = {
    implicit val mat: Materializer = app.fold(servlet.materializer)(_.materializer)
    import play.core.Execution.Implicits.trampoline

    val actionFuture = {
      def createActionFuture = Future(action(requestHeader))(mat.executionContext)
      // if async is un-supported we actually await the current future
      // and block the request thread :(
      if (request.isAsyncSupported) createActionFuture
      else Future.successful(Await.result(createActionFuture, Duration.Inf))
    }

    for {
      bodyParser <- actionFuture
      actionResult <- {
        val body = modelConversion.convertRequestBody(request)
        (body match {
          case None         => bodyParser.run()
          case Some(source) =>
            // if async is un-supported we actually await the current future
            // and block the request thread :(
            if (request.isAsyncSupported) bodyParser.run(source)
            else Future.successful(Await.result(bodyParser.run(source), Duration.Inf))
        }).recoverWith {
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
          request.getProtocol,
          errorHandler(app),
          response,
          request.isAsyncSupported
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
