package demo

import play.api.mvc.{ AbstractController, ControllerComponents, EssentialFilter }
import play.api.routing.sird._
import play.api.routing.Router.Routes
import play.api.routing.{ Router, SimpleRouter }
import play.api.{ Application, ApplicationLoader, BuiltInComponentsFromContext }

class DemoApplicationLoader extends ApplicationLoader {

  class DemoController(components: ControllerComponents) extends AbstractController(components) {

    def home = Action { implicit request =>
      Ok(s"hase ${request.body}")
    }

  }

  override def load(context: ApplicationLoader.Context): Application = {
    new BuiltInComponentsFromContext(context) {
      val demoController = new DemoController(controllerComponents)
      override def router: Router                    = new SimpleRouter {
        override def routes: Routes = {
          case GET(p"/") => demoController.home
          case POST(p"/hase") => demoController.home
        }
      }
      override def httpFilters: Seq[EssentialFilter] = Nil
    }.application
  }

}
