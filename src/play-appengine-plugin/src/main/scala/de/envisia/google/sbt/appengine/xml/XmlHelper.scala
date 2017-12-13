package de.envisia.google.sbt.appengine.xml

private[appengine] object XmlHelper {

  def addChild(n: scala.xml.Node, newChild: scala.xml.Node) = n match {
    case scala.xml.Elem(prefix, l, attribs, scope, child @ _*) =>
      scala.xml.Elem.apply(prefix, l, attribs, scope, true, child ++ newChild : _*)
    case _ => sys.error("Can only add children to elements!")
  }

}
