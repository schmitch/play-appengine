package play.core.server

import java.io.File
import java.net.InetSocketAddress
import javax.servlet.annotation.{ WebListener, WebServlet }
import javax.servlet.http.{ HttpServlet, HttpServletRequest, HttpServletResponse }
import javax.servlet.{ ServletContextEvent, ServletContextListener }

import akka.stream.Materializer
import play.api._
import play.core.ApplicationProvider
import play.core.server.servlet.PlayRequestHandler

import scala.util.{ Success, Try }

@WebListener
@WebServlet(name = "play", urlPatterns = Array("/*"), asyncSupported = true)
final class PlayServlet extends HttpServlet with ServletContextListener with Server {

  // FIXME: change depending on context, maybe we need two versions, one for prod and one for dev?
  def mode: Mode = Mode.Prod

  @volatile
  private var application: Application = _

  private def start(sce: ServletContextEvent) = {
    val rootDir = sce.getServletContext.getRealPath("/")

    val application: Application = {

      val environment = Environment(new File(rootDir), this.getClass.getClassLoader, mode)
      val context = ApplicationLoader.createContext(environment)
      val loader = ApplicationLoader(context)
      loader.load(context)
    }

    application
  }

  override def contextInitialized(sce: ServletContextEvent): Unit = {
    application = start(sce)
    Play.start(application)
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

  override def httpPort: Option[Int] = config.port
  override def httpsPort: Option[Int] = config.sslPort
  override def mainAddress(): InetSocketAddress = {
    val port: Int = httpsPort.orElse(httpPort).getOrElse(80)
    InetSocketAddress.createUnresolved(config.address, port)
  }

  /** request handling */
  private val requestHandler: PlayRequestHandler = new PlayRequestHandler(this)

  override protected def service(request: HttpServletRequest, resp: HttpServletResponse): Unit = {
    println("X")
    requestHandler.handle(request, resp)
  }

}
