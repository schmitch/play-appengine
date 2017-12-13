package de.envisia.google.sbt.appengine

import java.nio.file.Files

import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.Import.WebKeys.{ assets, jsFilter }
import sbt.Keys._
import sbt._
import com.typesafe.sbt.web.SbtWeb.autoImport._
import de.envisia.google.sbt.appengine.xml.XmlHelper
import play.TemplateImports
import play.sbt.routes.{ RoutesCompiler, RoutesKeys }
import play.twirl.sbt.Import.TwirlKeys
import play.twirl.sbt.SbtTwirl

import scala.collection.JavaConverters._
import scala.xml.XML

object AppenginePlayPlugin extends AutoPlugin {
  override def requires = WebappPlugin && SbtTwirl && SbtJsTask && RoutesCompiler

  object autoImport {
    val PlayVersion = settingKey[String]("play version")
  }

  import autoImport._
  import WebappPlugin.autoImport._

  private def updateWebXmlTask: Def.Initialize[Task[Seq[(File, String)]]] = Def.task {
    val baseXml = <web-app xmlns="http://java.sun.com/xml/ns/j2ee" version="3.1"></web-app>
    val webXml = (target in webappPrepare).value / "WEB-INF" / "web.xml"
    val xml = {
      if (webXml.exists()) {
        XML.loadFile(webXml)
      } else {
        webXml.createNewFile()
        baseXml
      }
    }

    val newElement = XmlHelper.addChild(
      xml.headOption.getOrElse(baseXml),
      <listener><listener-class>play.core.server.PlayServlet</listener-class></listener>
    )
    XML.save(webXml.toString, newElement)
    Nil
  }

  lazy val serviceSettings = Seq[Setting[_]](
    PlayVersion := Versions.PlayVersion,
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-encoding", "utf8"),
    javacOptions in Compile ++= Seq("-encoding", "utf8", "-g"),
    javacOptions in (Compile, doc) := List("-encoding", "utf8"),
    libraryDependencies += "com.typesafe.play" %% "play-server" % PlayVersion.value,
    libraryDependencies += "com.typesafe.play" %% "play-test"   % PlayVersion.value % "test",
    parallelExecution in Test := false,
    fork in Test := true,
    testOptions in Test += Tests.Argument(TestFrameworks.Specs2, "sequential", "true", "junitxml", "console"),
    testOptions in Test += Tests.Argument(TestFrameworks.JUnit, "--ignore-runners=org.specs2.runner.JUnitRunner"),
    watchSources ++= {
      ((sourceDirectory in Compile).value ** "*" --- (sourceDirectory in Assets).value ** "*").get
    },
    // THE `in Compile` IS IMPORTANT!
    // Keys.run in Compile := PlayRun.playDefaultRunTask.evaluated,
    // mainClass in (Compile, Keys.run) := Some("play.core.server.DevServerStart"),
    ivyLoggingLevel := UpdateLogging.DownloadOnly,
    // by default, compile any routes files in the root named "routes" or "*.routes"
    sources in (Compile, RoutesKeys.routes) ++= {
      val dirs = (unmanagedResourceDirectories in Compile).value
      (dirs * "routes").get ++ (dirs * "*.routes").get
    },
    webappPrepare := updateWebXmlTask.dependsOn(webappPrepare).value
  )

  lazy val defaultScalaSettings = Seq[Setting[_]](
    TwirlKeys.templateImports ++= TemplateImports.defaultScalaTemplateImports.asScala
  )

  lazy val webSettings = Seq[Setting[_]](
    TwirlKeys.constructorAnnotations += "@javax.inject.Inject()",
    RoutesKeys.routesImport ++= Seq("controllers.Assets.Asset"),
    // sbt-web
    jsFilter in Assets := new PatternFilter("""[^_].*\.js""".r.pattern),
    WebKeys.stagingDirectory := WebKeys.stagingDirectory.value / "public",
    /*
    playAssetsWithCompilation := {
      val ignore = ((assets in Assets) ?).value
      getPlayAssetsWithCompilation((compile in Compile).value)
    },
    // Assets for run mode
    PlayRun.playPrefixAndAssetsSetting,
    PlayRun.playAllAssetsSetting,
    assetsPrefix := "public/",
    // Assets for distribution
    WebKeys.packagePrefix in Assets := assetsPrefix.value,
    // Assets for testing
    public in TestAssets := (public in TestAssets).value / assetsPrefix.value,
     */
    fullClasspath in Test += Attributed.blank((assets in TestAssets).value.getParentFile)
  )

  override def projectSettings: Seq[Def.Setting[_]] = {
    serviceSettings ++ webSettings ++ defaultScalaSettings
  }

}
