package demo

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import play.api._
import play.api.mvc.{ AbstractController, ControllerComponents, EssentialFilter }
import play.api.routing.sird._
import play.api.routing.Router.Routes
import play.api.routing.{ Router, SimpleRouter }
import play.api.{ Application, ApplicationLoader, BuiltInComponentsFromContext }

class DemoApplicationLoader extends ApplicationLoader {

  private val logger = Logger(this.getClass)

  class DemoController(components: ControllerComponents) extends AbstractController(components) {

    val int = new AtomicInteger(0)
    val background = new Thread(new Runnable {
      override def run(): Unit = {
        while (!Thread.interrupted()) {
          int.incrementAndGet();
          Thread.sleep(15000)
        }
      }
    })

    val tp = Executors.newFixedThreadPool(1)
    tp.execute(background)

    def home = Action { implicit request =>
      logger.info("hello world")
      Ok(s"hase ${request.body} ${int.get()}")
    }

  }

  override def load(context: ApplicationLoader.Context): Application = {
    new BuiltInComponentsFromContext(context) {
      val demoController = new DemoController(controllerComponents)
      override def router: Router = new SimpleRouter {
        override def routes: Routes = {
          case GET(p"/")      => demoController.home
          case POST(p"/hase") => demoController.home
        }
      }
      override def httpFilters: Seq[EssentialFilter] = Nil
    }.application
  }

}
