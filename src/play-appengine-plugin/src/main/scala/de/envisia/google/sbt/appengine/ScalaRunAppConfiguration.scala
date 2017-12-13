package de.envisia.google.sbt.appengine

import java.io.File

import com.google.cloud.tools.appengine.api.devserver.RunConfiguration

import scala.collection.JavaConverters._

case class ScalaRunAppConfiguration(
    services: Seq[File],
    defaultBucketName: String = "appengine",
    port: Int = 9090
) extends RunConfiguration {
  // Must Have Settings
  override lazy val getServices: java.util.List[File] = {
    new java.util.ArrayList[File](services.asJava)
  }
  override lazy val getHost: String = "localhost"
  override lazy val getPort: Integer = port

  // JVM Settings
  override lazy val getAdditionalArguments: java.util.List[String] = java.util.Collections.emptyList()
  override lazy val getJvmFlags: java.util.List[String] = java.util.Collections.emptyList()

  // Useful settings
  override lazy val getAutomaticRestart: java.lang.Boolean = true
  override lazy val getEnvironment: java.util.Map[String, String] = Map("PLAYFRAMEWORK_MODE" -> "Dev").asJava
  override lazy val getDefaultGcsBucketName: String = defaultBucketName

  // nullable settings
  override def getRuntime: String = null
  override def getAdminHost: String = null
  override def getAdminPort: Integer = null
  override def getMaxModuleInstances: Integer = null
  override def getDevAppserverLogLevel: String = null
  override def getSkipSdkUpdateCheck: java.lang.Boolean = null
  override def getUseMtimeFileWatcher: java.lang.Boolean = null
  override def getAuthDomain: String = null
  override def getStoragePath: File = null
  override def getPythonStartupArgs: String = null
  override def getApiPort: Integer = null
  override def getLogLevel: String = null
  override def getClearDatastore: java.lang.Boolean = null
  override def getThreadsafeOverride: String = null
  override def getAllowSkippedFiles: java.lang.Boolean = null
  override def getPythonStartupScript: String = null
  override def getDatastorePath: File = null
  override def getCustomEntrypoint: String = null
}
