name := "play-appengine"
organization in ThisBuild := "de.envisia.play.servlet"
scalaVersion in ThisBuild := "2.12.4"

val jettyVersion = "9.4.7.v20170914"

val `play-servlet` = (project in file("src/play-servlet"))
    .settings(
      libraryDependencies ++= Seq(
        "com.typesafe.play" %% "play" % "2.6.6",
        "com.typesafe.play" %% "play-guice" % "2.6.6",
        "com.typesafe.play" %% "play-server" % "2.6.6",
        "com.typesafe.play" %% "play-logback" % "2.6.6",
        "com.google.inject.extensions" % "guice-servlet" % "4.1.0",
        "javax.servlet" % "javax.servlet-api" % "3.1.0" % Provided,

        "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
        "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % Test,
        "org.eclipse.jetty" % "jetty-webapp" % jettyVersion % Test,
      ),
      containerPort := 9090,
    )
    .enablePlugins(JettyPlugin)

val `play-appengine-plugin` = (project in file("src/play-appengine-plugin"))

val root = (project in file(".")).settings(
  version := "0.1.0-SNAPSHOT",
  sbtPlugin := true,
  libraryDependencies += "com.google.cloud.tools" % "appengine-plugins-core" % "0.3.6"
)

// REF: https://github.com/GoogleCloudPlatform/app-gradle-plugin

/*
javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

initialize := {
  val _ = initialize.value
  if (sys.props("java.specification.version") != "1.8")
    sys.error("Java 8 is required for this project.")
}
*/