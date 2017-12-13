package de.envisia.google.sbt.appengine

sealed trait DevServerVersion

object DevServerVersion {

  case object V1 extends DevServerVersion
  case object V2 extends DevServerVersion

}
