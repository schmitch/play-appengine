package demo

import javax.servlet.http._

class SchnarchServlet extends HttpServlet {

  override def doGet(req: HttpServletRequest, res: HttpServletResponse): Unit = {

    res.getWriter().println("5")

  }

}