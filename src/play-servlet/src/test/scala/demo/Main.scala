package demo

import java.io.File
import java.{ lang, util }

import scala.collection.JavaConverters._
import com.google.cloud.tools.appengine.api.devserver.{ DefaultStopConfiguration, RunConfiguration }
import com.google.cloud.tools.appengine.cloudsdk.{ CloudSdk, CloudSdkAppEngineDevServer2 }
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletHolder
import org.eclipse.jetty.webapp.WebAppContext
import play.core.server.PlayServlet

object Main {

  def main3(args: Array[String]): Unit = {
    val server = new Server(9081)

    val servlet = new PlayServlet

    val context = new WebAppContext()
    context.setResourceBase("")
    context.getServletContext.setExtendedListenerTypes(true)
    context.addEventListener(servlet)

    context.setServer(server)
    context.start()

    server.setHandler(context)

    server.start()
    server.join()
  }

  def main(args: Array[String]): Unit = {
    main3(args)
  }

}
