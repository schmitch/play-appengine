package play.core.server

import java.io.File
import java.net.InetSocketAddress
import javax.servlet.annotation.WebListener
import javax.servlet.http.{ HttpServlet, HttpServletRequest, HttpServletResponse }
import javax.servlet.{ ServletContextEvent, ServletContextListener }

import akka.stream.Materializer
import play.api._
import play.core.ApplicationProvider
import play.core.server.servlet.PlayRequestHandler

import scala.util.control.NonFatal
import scala.util.{ Success, Try }

// We only need to register the WebListener, since the Servlet will register itself
// @WebServlet(name = "play", urlPatterns = Array("/*"), asyncSupported = true)
@WebListener
final class PlayServlet extends HttpServlet with ServletContextListener with Server {

  private val logger = Logger(this.getClass)

  def mode: Mode = {
    Option(System.getenv("PLAYFRAMEWORK_MODE"))
      .filter(_ == "Dev")
      .map(_ => Mode.Dev)
      .getOrElse(Mode.Prod)
  }

  @volatile
  private var application: Application = _

  private def start(sce: ServletContextEvent) = {
    val rootDir = sce.getServletContext.getRealPath("/")

    val application: Application = {

      val environment = Environment(new File(rootDir), this.getClass.getClassLoader, mode)
      val context     = ApplicationLoader.createContext(environment)
      val loader      = ApplicationLoader(context)
      loader.load(context)
    }

    application
  }

  override def contextInitialized(sce: ServletContextEvent): Unit = {
    application = start(sce)
    Play.start(application)

    val registration = sce.getServletContext.addServlet("play", this)
    try {
      registration.setAsyncSupported(true)
    } catch {
      case _ @(_: IllegalStateException | _: NullPointerException) => logger.error("async is not supported")
    }
    registration.addMapping("/*")
  }

  override def contextDestroyed(sce: ServletContextEvent): Unit = {
    if (application != null) {
      Play.stop(application)
    }
  }

  def materializer: Materializer = application.materializer

  /** server related stuff, fixme: move to servlet-server? */
  def config: ServerConfig = ???
  def applicationProvider: ApplicationProvider = new ApplicationProvider {
    override def get: Try[Application] = Success(application)
  }

  override def httpPort: Option[Int]  = config.port
  override def httpsPort: Option[Int] = config.sslPort
  override def mainAddress(): InetSocketAddress = {
    val port: Int = httpsPort.orElse(httpPort).getOrElse(80)
    InetSocketAddress.createUnresolved(config.address, port)
  }

  /** request handling */
  private val requestHandler: PlayRequestHandler = new PlayRequestHandler(this)

  override protected def service(request: HttpServletRequest, resp: HttpServletResponse): Unit = {
    requestHandler.handle(request, resp)
  }

}
