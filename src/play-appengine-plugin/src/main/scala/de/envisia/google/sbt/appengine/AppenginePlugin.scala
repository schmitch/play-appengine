package de.envisia.google.sbt.appengine

import java.io.File
import java.nio.file.{ FileVisitOption, Files }
import java.util.Comparator

import com.google.cloud.tools.appengine.api.deploy.{ DefaultDeployConfiguration, DefaultStageStandardConfiguration, StageStandardConfiguration }
import com.google.cloud.tools.appengine.cloudsdk._
import com.google.cloud.tools.appengine.api.devserver.{ DefaultStopConfiguration, RunConfiguration }
import com.google.cloud.tools.appengine.cloudsdk.process.{ NonZeroExceptionExitListener, ProcessOutputLineListener }
import sbt._
import sbt.Keys._

import scala.collection.JavaConverters._

object AppenginePlugin extends AutoPlugin {
  override def requires = WebappPlugin

  object autoImport {
    lazy val runApp    = taskKey[Unit]("run task")
    lazy val deployApp = taskKey[Unit]("deploy task")
  }

  import autoImport._
  import WebappPlugin.autoImport._

  class SbtLogger(message: (=> String) => Unit) extends ProcessOutputLineListener {
    override def onOutputLine(line: String): Unit = message(line)
  }

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    runApp := {
      val logger = streams.value.log
      webappPrepare.value

      val targetDirectory = (target in webappPrepare).value
      println(s"running sbt web: $targetDirectory")

      val sdk = new CloudSdk.Builder()
        .sdkPath(null)
        .exitListener(new NonZeroExceptionExitListener)
        .addStdErrLineListener(new SbtLogger(logger.err))
        .addStdOutLineListener(new SbtLogger(logger.out))
        //          .appCommandMetricsEnvironment(getClass().getPackage().getImplementationTitle())
        //          .appCommandMetricsEnvironmentVersion(getClass().getPackage().getImplementationVersion())
        .build()

      //      val stop = new DefaultStopConfiguration()
      //      stop.setAdminHost("localhost")
      //      stop.setAdminPort(9091)

      val services = targetDirectory :: Nil

      val runConfig = new RunConfiguration {
        override def getServices: java.util.List[File] = new java.util.ArrayList[File](services.asJava)

        override def getAdminHost: String  = null
        override def getAdminPort: Integer = null

        override def getDatastorePath: File = null

        override def getCustomEntrypoint: String                    = null
        override def getThreadsafeOverride: String                  = null
        override def getPythonStartupArgs: String                   = null
        override def getPythonStartupScript: String                 = null
        override def getAdditionalArguments: java.util.List[String] = java.util.Collections.emptyList()
        override def getJvmFlags: java.util.List[String]            = java.util.Collections.emptyList()

        override def getApiPort: Integer = null

        override def getLogLevel: String             = "info"
        override def getDevAppserverLogLevel: String = "info"

        override def getSkipSdkUpdateCheck: java.lang.Boolean  = true
        override def getUseMtimeFileWatcher: java.lang.Boolean = true
        override def getMaxModuleInstances: Integer            = 1
        override def getAuthDomain: String                     = "envisia.de"
        override def getStoragePath: File                      = new File("/Users/schmitch/z_important/tmp/stor")

        override def getAutomaticRestart: java.lang.Boolean = true
        override def getClearDatastore: java.lang.Boolean   = true

        override def getAllowSkippedFiles: java.lang.Boolean = true
        override def getDefaultGcsBucketName: String         = "envisia"

        override def getEnvironment: java.util.Map[String, String] = Map("PLAYFRAMEWORK_MODE" -> "Dev").asJava

        override def getRuntime: String = "java8"

        override def getHost: String  = "localhost"
        override def getPort: Integer = 9081
      }
//      new CloudSdkAppEngineDevServer2(sdk).run(runConfig)
      new CloudSdkAppEngineDevServer1(sdk).run(runConfig)
    },
    deployApp := {
      val logger = streams.value.log
      webappPrepare.value

      val explodedDirectory = (target in webappPrepare).value
      val stageDirectory    = target.value / "exploded-service"
      if (Files.exists(stageDirectory.toPath)) {
        Files.walk(stageDirectory.toPath)
            .sorted(Comparator.reverseOrder())
            .iterator()
            .asScala
            .foreach(value => Files.deleteIfExists(value))
      }

      // Create a Cloud SDK// Create a Cloud SDK
      val sdk = new CloudSdk.Builder()
        .sdkPath(null)
        .exitListener(new NonZeroExceptionExitListener)
        .addStdErrLineListener(new SbtLogger(logger.err))
        .addStdOutLineListener(new SbtLogger(logger.out))
        //          .appCommandMetricsEnvironment(getClass().getPackage().getImplementationTitle())
        //          .appCommandMetricsEnvironmentVersion(getClass().getPackage().getImplementationVersion())
        .build()

      val stage              = new CloudSdkAppEngineStandardStaging(sdk)
      val stageConfiguration = new DefaultStageStandardConfiguration()
      stageConfiguration.setSourceDirectory(explodedDirectory)
      stageConfiguration.setStagingDirectory(stageDirectory)
      stageConfiguration.setRuntime("java8")
      stage.stageStandard(stageConfiguration)

      // Create a deployment
      val deployment = new CloudSdkAppEngineDeployment(sdk)

      // Configure deployment
      val configuration = new DefaultDeployConfiguration
      configuration.setDeployables(java.util.Arrays.asList(stageDirectory / "app.yaml"))
      configuration.setPromote(true)
      configuration.setStopPreviousVersion(true)
      //configuration.setVersion("v1")
      configuration.setProject("infinite-alcove-164114")

      // deploy
      deployment.deploy(configuration)
    }
  )

}
