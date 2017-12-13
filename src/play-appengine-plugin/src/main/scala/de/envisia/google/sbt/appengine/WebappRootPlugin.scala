package de.envisia.google.sbt.appengine

import sbt.{ AutoPlugin, Plugins }
import sbt.plugins.JvmPlugin

object WebappRootPlugin extends AutoPlugin {
  override def requires: Plugins      = JvmPlugin
}
