package demo

import java.nio.file.FileSystems

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{ ServletContextHandler, ServletHandler, ServletHolder }
import org.eclipse.jetty.webapp.WebAppContext
import play.core.server.PlayServlet

object Main {

  def main(args: Array[String]): Unit = {
    val server = new Server(9081)

    val servlet = new PlayServlet

    val context = new WebAppContext()
    context.setResourceBase("")
    context.getServletContext.setExtendedListenerTypes(true)
    context.addEventListener(servlet)
    context.addServlet(new ServletHolder(servlet), "/*")

    context.setServer(server)
    context.start()

    server.setHandler(context)

    server.start()
    server.join()
  }

}
