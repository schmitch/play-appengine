package demo

import play.api._
import play.api.mvc._
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.{ Inject, Singleton }

@Singleton
class DemoController @Inject()(components: ControllerComponents) extends AbstractController(components) {
  private val logger = Logger(this.getClass)


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