package de.envisia.google.sbt.appengine

import java.io.File
import java.nio.file.Files
import java.util.Comparator

import com.google.cloud.tools.appengine.api.deploy.{ DefaultDeployConfiguration, DefaultStageStandardConfiguration }
import com.google.cloud.tools.appengine.cloudsdk._
import com.google.cloud.tools.appengine.cloudsdk.process.{ NonZeroExceptionExitListener, ProcessOutputLineListener }
import sbt.Def.Initialize
import sbt.Keys.{ watchSources, _ }
import sbt._

import scala.collection.JavaConverters._

object AppenginePlugin extends AutoPlugin {
  override def requires: Plugins      = WebappRootPlugin
  override def trigger: PluginTrigger = allRequirements

  object autoImport {
    object AE {
      // Both
      lazy val runtime          = settingKey[String]("appengine runtime version")
      lazy val envVariables     = settingKey[Seq[(String, String)]]("appengine env variables")
      lazy val devServerVersion = settingKey[DevServerVersion]("appengine dev server version")
      // Development
      lazy val defaultBucketName = settingKey[Option[String]]("default bucket name")
      lazy val devEnvVariables   = settingKey[Seq[(String, String)]]("development env variables")
      // Deployment
      lazy val deployProject             = settingKey[String]("appengine project name")
      lazy val deployPromote             = settingKey[Boolean]("promote on deploy")
      lazy val deployStopPreviousVersion = settingKey[Boolean]("stop previous version on deploy")
      lazy val deployEnvVariables        = settingKey[Seq[(String, String)]]("deploy env variables")
    }

    lazy val runAll    = taskKey[Unit]("run appengine")
    lazy val deployAll = taskKey[Unit]("deploy task")
  }

  import WebappPlugin.autoImport._
  import autoImport._

  class SbtLogger(message: (=> String) => Unit) extends ProcessOutputLineListener {
    override def onOutputLine(line: String): Unit = message(line)
  }

  /** Projects that have the WebApp plugin enabled. */
  private lazy val webAppProjects: Initialize[Task[Seq[ProjectRef]]] = Def.task {
    val structure = buildStructure.value
    val projects  = structure.allProjectRefs
    for {
      projRef    <- projects
      proj       <- Project.getProject(projRef, structure).toList
      autoPlugin <- proj.autoPlugins if autoPlugin == WebappPlugin
    } yield projRef
  }

  private lazy val webAppDirectories: Initialize[Task[Seq[File]]] = Def.taskDyn {
    val projects = webAppProjects.value
    val filter   = ScopeFilter(inProjects(projects: _*))
    Def.task {
      (target in webappPrepare).all(filter).value
    }
  }

  private lazy val innerWatchSources: Initialize[Task[Seq[Watched.WatchSource]]] = Def.taskDyn {
    val projects = webAppProjects.value
    val filter   = ScopeFilter(inProjects(projects: _*))
    Def.task {
      watchSources.all(filter).value.flatten
    }
  }

  private lazy val defaultProjectTask = Def.taskDyn {
    val projects = webAppProjects.value
    val filter   = ScopeFilter(inProjects(projects: _*))
    Def.task {
      val values = webappAppengineWebXmlRead.all(filter).value
      val current = values.flatMap {
        case Some((node, ref)) =>
          val name = (node \\ "service").headOption.map(_.text)
          if (name.isEmpty || name.contains("default")) Some(ref)
          else None
        case None => None
      }

      if (current.length == 1) current.head
      else if (current.isEmpty) throw new Exception("could not detect a default project")
      else throw new Exception("multiple default projects detected, please check your appengine-web.xml")
    }
  }

  private def dynamicPrepareAll = Def.taskDyn {
    val projects = webAppProjects.value
    val filter   = ScopeFilter(inProjects(projects: _*))
    Def.task {
      webappPrepare.all(filter).value
    }
  }

  private def dontAggregate(keys: Scoped*): Seq[Setting[_]] = keys.map(aggregate in _ := false)

  private def runAppTask = Def.taskDyn {
    val logger = streams.value.log
    // runs all projects and filters out the default project
    val projects       = webAppProjects.value
    val defaultProject = defaultProjectTask.value
    // filters the projects based on the project name
    // (currently there is no way to actually compare the project ref directly)
    val filteredProjects = projects.filterNot(p => defaultProject.project == p.project)

    val filter       = ScopeFilter(inProjects(filteredProjects: _*))
    val singleFilter = ScopeFilter(inProjects(defaultProject))

    val devServerVersion = AE.devServerVersion.value

    Def.task {
      val defaultService = (target in webappPrepare).all(singleFilter).value
      val services       = (target in webappPrepare).all(filter).value

      // val stagedDirectories = target.all(filter).value.map(_ / "exploded-service")
      // println(s"Stage Directories: $stagedDirectories")

      val sdk = new CloudSdk.Builder()
        .sdkPath(null)
        .exitListener(new NonZeroExceptionExitListener)
        .addStdErrLineListener(new SbtLogger(logger.out))
        .addStdOutLineListener(new SbtLogger(logger.out))
        // .appCommandMetricsEnvironment(getClass().getPackage().getImplementationTitle())
        // .appCommandMetricsEnvironmentVersion(getClass().getPackage().getImplementationVersion())
        .build()

      // val stop = new DefaultStopConfiguration()
      // stop.setAdminHost("localhost")
      // stop.setAdminPort(9091)

      val runConfig = ScalaRunAppConfiguration(defaultService ++ services)
      val tooling = devServerVersion match {
        case DevServerVersion.V1 => new CloudSdkAppEngineDevServer1(sdk)
        case DevServerVersion.V2 => new CloudSdkAppEngineDevServer2(sdk)
      }
      tooling.run(runConfig)
    }
  }

  private def cleanDirectory(dir: File): Unit = {
    if (Files.exists(dir.toPath)) {
      Files
        .walk(dir.toPath)
        .sorted(Comparator.reverseOrder())
        .iterator()
        .asScala
        .foreach(value => Files.deleteIfExists(value))
    }
  }

  private def deployAppTask = Def.task {
    val logger              = streams.value.log
    val explodedDirectories = webAppDirectories.value
    val runtimeVersion      = AE.runtime.value

    val sdk = new CloudSdk.Builder()
      .sdkPath(null)
      .exitListener(new NonZeroExceptionExitListener)
      .addStdErrLineListener(new SbtLogger(logger.err))
      .addStdOutLineListener(new SbtLogger(logger.out))
      // .appCommandMetricsEnvironment(getClass().getPackage().getImplementationTitle())
      // .appCommandMetricsEnvironmentVersion(getClass().getPackage().getImplementationVersion())
      .build()

    // clean previous staging directory
    // and start staging all necessary files
    val stageDirectories = explodedDirectories.map(exploded => (exploded, exploded / "exploded-service"))
    stageDirectories.foreach {
      case (exploded, staged) =>
        cleanDirectory(staged)
        val stage              = new CloudSdkAppEngineStandardStaging(sdk)
        val stageConfiguration = new DefaultStageStandardConfiguration()
        stageConfiguration.setSourceDirectory(exploded)
        stageConfiguration.setStagingDirectory(staged)
        stageConfiguration.setRuntime(runtimeVersion)
        stage.stageStandard(stageConfiguration)
    }
    val appYamls = stageDirectories.map(_._2 / "app.yaml")

    // Create a deployment
    val deployment = new CloudSdkAppEngineDeployment(sdk)

    // Configure deployment
    val configuration = new DefaultDeployConfiguration
    configuration.setDeployables(appYamls.asJava)
    configuration.setPromote(AE.deployPromote.value)
    configuration.setStopPreviousVersion(AE.deployStopPreviousVersion.value)
    configuration.setProject(AE.deployProject.value)
    // configuration.setVersion("v1")

    // deploy
    deployment.deploy(configuration)
  }

  private def getProjectName = {
    val projectName = sys.env.getOrElse(
      "APPENGINE_PROJECT",
      throw new Exception(
        "Please set either `AE.deployProject` or the Environment Variable `APPENGINE_PROJECT` to your AppEngine project name"
      )
    )
    projectName
  }

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      // Both
      AE.runtime := "java8",
      AE.envVariables := Nil,
      AE.devServerVersion := DevServerVersion.V2,
      // Development
      AE.defaultBucketName := None,
      AE.devEnvVariables := ("PLAYFRAMEWORK_MODE" -> "Dev") +: AE.envVariables.value,
      // Deploy
      AE.deployProject := getProjectName,
      AE.deployPromote := true,
      AE.deployStopPreviousVersion := true,
      AE.deployEnvVariables := AE.envVariables.value,
      // Run Tasks
      runAll := runAppTask.dependsOn(dynamicPrepareAll).value,
      deployAll := deployAppTask.dependsOn(dynamicPrepareAll).value,
      watchSources ++= innerWatchSources.value
    ) ++ dontAggregate(runAll, deployAll)

}
