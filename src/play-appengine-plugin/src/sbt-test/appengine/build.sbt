
name := "envisia-appengine-test"

scalaVersion := "2.12.4"

version := "0.1.0-SNAPSHOT"

Option(System.getProperty("plugin.version")) match {
  case None =>
    throw new RuntimeException(
      """|The system property 'plugin.version' is not defined.
         |Please specify this property using the SBT flag -D.""".stripMargin)
  case Some(pluginVersion) =>
    libraryDependencies += "de.envisia.play.servlet" %% "play-servlet" % pluginVersion
}

libraryDependencies += "com.google.appengine" % "appengine-api-1.0-sdk" % "1.9.59"
updateOptions := updateOptions.value.withGigahorse(false)
// libraryDependencies += "javax.servlet" % "javax.servlet-api" % "3.0.1" % "provided"


// libraryDependencies += "com.google.cloud" % "google-cloud-logging-logback" % "0.28.0-alpha"

lazy val cronJobModule = (project in file("module")).enablePlugins(WebappPlugin)
.settings(libraryDependencies += "javax.servlet" % "javax.servlet-api" % "3.1.0" % Provided)

lazy val root = (project in file(".")).enablePlugins(WebappRootPlugin, WebappPlugin, AppenginePlayPlugin)

AE.deployProject := "hase"
//webappPostProcess := {
//  val s = streams.value.log
//  webappDir =>
//    def listFiles(level: Int)(f: File): Unit = {
//      val indent = ((1 until level) map { _ => "  " }).mkString
//      if (f.isDirectory) {
//        s.info(indent + f.getName + "/")
//        f.listFiles foreach { listFiles(level + 1) }
//      } else s.info(indent + f.getName)
//    }
//    listFiles(1)(webappDir)
//}