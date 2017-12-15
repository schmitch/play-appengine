package de.envisia.google.sbt.appengine

import java.util.jar.Manifest

import de.envisia.google.sbt.appengine.xml.XmlHelper
import sbt._
import sbt.Def.taskKey
import sbt.Def.settingKey
import sbt.Keys._
import sbt.FilesInfo.lastModified
import sbt.FilesInfo.exists

import scala.xml.XML

object WebappPlugin extends AutoPlugin {

  object autoImport {
    lazy val webappPrepare               = taskKey[Seq[(File, String)]]("prepare webapp contents for packaging")
    lazy val webappPostProcess           = taskKey[File => Unit]("additional task after preparing the webapp")
    lazy val webappWebInfClasses         = settingKey[Boolean]("use WEB-INF/classes instead of WEB-INF/lib")
    lazy val webappAppengineWebXmlAppend = taskKey[Seq[scala.xml.Node]]("append xml to appengine-web.xml if it exists")
    lazy val webappAppengineWebXmlRead =
      taskKey[Option[(scala.xml.Node, ProjectRef)]]("returns the appengine-web.xml inside the target directory")
  }

  import autoImport._

  override def requires = plugins.JvmPlugin

  override def projectSettings: Seq[Setting[_]] = {
    Seq(
      webappAppengineWebXmlAppend := Nil,
      webappAppengineWebXmlRead := webappAppengineWebXmlReadTask.value,
      sourceDirectory in webappPrepare := (sourceDirectory in Compile).value / "webapp",
      target in webappPrepare := (target in Compile).value / "webapp",
      webappPrepare := webappPrepareTask.dependsOn(webappAppengineWebXmlAppendTask).value,
      webappPostProcess := { _ =>
        ()
      },
      webappWebInfClasses := false,
      Compat.watchSourceSetting
    )
  }

  private def webappAppengineWebXmlAppendTask: Def.Initialize[Task[Seq[(File, String)]]] = Def.taskDyn {
    val appengineWebXml = (target in webappPrepare).value / "WEB-INF" / "appengine-web.xml"
    if (appengineWebXml.exists()) {
      val xml    = XML.loadFile(appengineWebXml)
      val values = webappAppengineWebXmlAppend.value
      Def.task {
        val finalValue = values.foldLeft(xml)((v1, v2) => XmlHelper.addChild(v1, v2))
        XML.save(appengineWebXml.toString, finalValue)
        Nil
      }
    } else {
      Def.task {
        Nil
      }
    }

  }

  private def webappAppengineWebXmlReadTask = Def.task {
    val appengineWebXml = (target in webappPrepare).value / "WEB-INF" / "appengine-web.xml"
    if (appengineWebXml.exists()) Some((XML.loadFile(appengineWebXml), thisProjectRef.value))
    else None
  }

  private def webappPrepareTask = Def.task {
    def cacheify(name: String, dest: File => Option[File], in: Set[File]): Set[File] =
      Compat
        .cached(streams.value.cacheDirectory / "xsbt-web-plugin" / name, lastModified, exists)({
          (inChanges, outChanges) =>
            // toss out removed files
            for {
              removed  <- inChanges.removed
              toRemove <- dest(removed)
            } yield IO.delete(toRemove)

            // apply and report changes
            for {
              in  <- inChanges.added ++ inChanges.modified -- inChanges.removed
              out <- dest(in)
              _ = IO.copyFile(in, out)
            } yield out
        })
        .apply(in)

    val webappSrcDir = (sourceDirectory in webappPrepare).value
    val webappTarget = (target in webappPrepare).value

    val classpath    = (fullClasspath in Runtime).value
    val webInfDir    = webappTarget / "WEB-INF"
    val webappLibDir = webInfDir / "lib"

    cacheify(
      "webapp", { in =>
        for {
          f <- Some(in)
          if !f.isDirectory
          r <- IO.relativizeFile(webappSrcDir, f)
        } yield IO.resolve(webappTarget, r)
      },
      (webappSrcDir ** "*").get.toSet
    )

    val m = (mappings in (Compile, packageBin)).value
    val p = (packagedArtifact in (Compile, packageBin)).value._2

    if (webappWebInfClasses.value) {
      // copy this project's classes directly to WEB-INF/classes
      cacheify(
        "classes", { in =>
          m find {
            case (src, dest) => src == in
          } map {
            case (src, dest) =>
              webInfDir / "classes" / dest
          }
        },
        (m filter {
          case (src, dest) => !src.isDirectory
        } map {
          case (src, dest) =>
            src
        }).toSet
      )
    } else {
      // copy this project's classes as a .jar file in WEB-INF/lib
      cacheify(
        "lib-art", { in =>
          Some(webappLibDir / in.getName)
        },
        Set(p)
      )
    }

    // create .jar files for depended-on projects in WEB-INF/lib
    for {
      cpItem <- classpath.toList
      dir = cpItem.data
      if dir.isDirectory
      artEntry <- cpItem.metadata.entries find { e =>
        e.key.label == "artifact"
      }
      cpArt    = artEntry.value.asInstanceOf[Artifact]
      artifact = (packagedArtifact in (Compile, packageBin)).value._1
      if cpArt != artifact
      files = (dir ** "*").get flatMap { file =>
        if (!file.isDirectory)
          IO.relativize(dir, file) map { p =>
            (file, p)
          } else
          None
      }
      jarFile = cpArt.name + ".jar"
      _       = IO.jar(files, webappLibDir / jarFile, new Manifest)
    } yield ()

    // copy this project's library dependency .jar files to WEB-INF/lib
    cacheify(
      "lib-deps", { in =>
        Some(webappTarget / "WEB-INF" / "lib" / in.getName)
      },
      classpath.map(_.data).toSet filter { in =>
        !in.isDirectory && in.getName.endsWith(".jar")
      }
    )

    webappPostProcess.value(webappTarget)

    (webappTarget ** "*") pair (Path.relativeTo(webappTarget) | Path.flat)
  }

}
