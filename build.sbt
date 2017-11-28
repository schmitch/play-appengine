name := "play-appengine"
organization in ThisBuild := "de.envisia.play.servlet"
scalaVersion in ThisBuild := "2.12.4"
updateOptions in ThisBuild := updateOptions.value.withGigahorse(false)

val playVersion = "2.6.7"

val jettyVersion = "9.4.7.v20170914"
val appenginePluginsCoreVersion = "0.3.9"

val `play-servlet` = (project in file("src/play-servlet"))
    .settings(
      libraryDependencies ++= Seq(
        "com.typesafe.play" %% "play" % playVersion,
        "com.typesafe.play" %% "play-guice" % playVersion,
        "com.typesafe.play" %% "play-server" % playVersion,
        "com.typesafe.play" %% "play-logback" % playVersion,
        // "com.google.inject.extensions" % "guice-servlet" % "4.1.0",
        "javax.servlet" % "javax.servlet-api" % "3.1.0" % Provided,

//        "com.google.cloud.tools" % "appengine-plugins-core" % appenginePluginsCoreVersion,

        "org.eclipse.jetty" % "jetty-server" % jettyVersion % Test,
        "org.eclipse.jetty" % "jetty-servlet" % jettyVersion % Test,
        "org.eclipse.jetty" % "jetty-webapp" % jettyVersion % Test,
      ),
      // containerPort := 9090,
    )
// .enablePlugins(JettyPlugin)

val `play-appengine-plugin` = (project in file("src/play-appengine-plugin"))
    .settings(
      sbtPlugin := true,
      libraryDependencies ++= Seq(
        "com.google.cloud.tools" % "appengine-plugins-core" % appenginePluginsCoreVersion,
        "com.lightbend.play" %% "play-file-watch" % "1.1.6"
      )
    )

val root = (project in file("."))
    .settings(
      sbtPlugin := true,
    )
    .dependsOn(
      `play-servlet`,
      `play-appengine-plugin`
    )
    .aggregate(
      `play-servlet`,
      `play-appengine-plugin`
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