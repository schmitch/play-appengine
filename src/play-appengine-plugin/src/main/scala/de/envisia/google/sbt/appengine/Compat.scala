package de.envisia.google.sbt.appengine

import sbt._
import sbt.Keys._
import sbt.internal.io.Source
import sbt.util.CacheStoreFactory
import FileInfo.Style
import WebappPlugin.autoImport.webappPrepare
import sbt.util.FilesInfo.{ exists, lastModified }

object Compat {

  type Process = scala.sys.process.Process

  def forkOptionsWithRunJVMOptions(options: Seq[String]) =
    ForkOptions().withRunJVMOptions(options.toVector)

  val watchSourceSetting = watchSources += new Source((sourceDirectory in webappPrepare).value, AllPassFilter, NothingFilter)

  def cached(cacheBaseDirectory: File, inStyle: Style, outStyle: Style)(action: (ChangeReport[File], ChangeReport[File]) => Set[File]): Set[File] => Set[File] =
    sbt.util.FileFunction.cached(CacheStoreFactory(cacheBaseDirectory), inStyle = inStyle, outStyle = outStyle)(action = action)


  def cacheify(cacheDirectory: File, name: String, dest: File => Option[File], in: Set[File]): Set[File] = {
    Compat
        .cached(cacheDirectory / name, lastModified, exists)({
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
  }

}
