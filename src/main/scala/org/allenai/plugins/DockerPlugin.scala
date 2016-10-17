package org.allenai.plugins

import sbt.{ AutoPlugin, Plugins }
import sbt.plugins.JvmPlugin

/** Plugin for building docker images. */
object DockerPlugin extends AutoPlugin {
  /** Requires the JvmPlugin, since this will be building a jar dependency tree. */
  override def requires: Plugins = JvmPlugin

  object autoImport {
  }
}
