package org.allenai.plugins

import sbt.{
  relativeTo,
  settingKey,
  taskKey,
  AutoPlugin,
  Compile,
  Def,
  IO,
  Keys,
  PathFinder,
  Plugins,
  SettingKey,
  Task,
  TaskKey
}
import sbt.plugins.JvmPlugin

import java.io.File

import scala.sys.process.Process

/** Plugin for building docker images. */
object DockerBuildPlugin extends AutoPlugin {
  val AI2_PRIVATE_REGISTRY = "allenai-docker-private-docker.bintray.io"

  val DEFAULT_BASE_IMAGE = AI2_PRIVATE_REGISTRY + "/openjdk:8"

  /** The name of the startup script, located in this class's resources. This will also be the name
    * of the script in the `bin` directory in the generated image.
    */
  val STARTUP_SCRIPT_NAME = "run-docker.sh"

  /** The string (one full line) that separates the autogenerated Dockerfile contents from any
    * user-provided additions.
    */
  val DOCKERFILE_SIGIL = "#+" * 50

  /** Requires the JvmPlugin, since this will be building a jar dependency tree. */
  override def requires: Plugins = JvmPlugin

  object autoImport {
    ////////////////////////////////////////////////////////////////////////////////////////////////
    // The following settings affect both the images and Dockerfiles generated by this plugin. When
    // you update these settings in a build.sbt file, you'll want to re-generate your Dockerfiles.
    ////////////////////////////////////////////////////////////////////////////////////////////////

    val dockerfileLocation: SettingKey[File] = settingKey[File](
      "The location of the Dockerfile to use in building the main project image. Defaults to " +
        "`srcDirectory.value + \"docker/Dockerfile\"`, typically \"src/main/docker/Dockerfile\"."
    )

    // The following three settings control how the generated image is tagged. The image portion of
    // image tags will be, for the main image:
    //   ${dockerImageRegistryHost}/${dockerImageNamePrefix}/${dockerImageName}
    // and for the dependency image will be:
    //   ${dockerImageRegistryHost}/${dockerImageNamePrefix}/${dockerImageName}-dependency
    //
    // See the documentation for details on which tags will be used by `dockerBuild` and
    // `dockerPush`.
    val dockerImageRegistryHost: SettingKey[String] = settingKey[String](
      "The base name of the image you're creating. Defaults to " + AI2_PRIVATE_REGISTRY + "."
    )
    val dockerImageNamePrefix: SettingKey[String] = settingKey[String](
      "The image name prefix (\"repository\", in Docker terms) of the image you're creating. " +
        "Defaults to organization.value.stripPrefix(\"org.allenai.\") . " +
        "This is typically the github repository name."
    )
    val dockerImageName: SettingKey[String] = settingKey[String](
      "The name of the image you're creating. Defaults to the sbt project name (the `name` " +
        "setting key)."
    )

    val dockerImageBase: SettingKey[String] = settingKey[String](
      "The base image to use when creating your image. Defaults to " + DEFAULT_BASE_IMAGE + "."
    )

    val dockerCopyMappings: SettingKey[Seq[(File, String)]] = settingKey[Seq[(File, String)]](
      "Mappings to add to the Docker image. Relative file paths will be interpreted as being " +
        "relative to the base directory (`baseDirectory.value`). See " +
        "http://www.scala-sbt.org/0.12.3/docs/Detailed-Topics/Mapping-Files.html for detailed " +
        "info on sbt mappings. Defaults to mapping src/main/{bin,conf} to {bin,conf} on the " +
        "image."
    )

    val dockerPorts: SettingKey[Seq[Int]] = settingKey[Seq[Int]](
      "The value(s) to use for EXPOSE when generating your Dockerfile. Defaults to `Seq.empty`."
    )

    val dockerMainArgs: SettingKey[Seq[String]] = settingKey[Seq[String]](
      "The value to use for CMD in order to pass default arguments to your application. Defaults " +
        "to `Seq.empty`."
    )

    val dockerWorkdir: SettingKey[String] = settingKey[String](
      "The value to use for WORKDIR when generating your Dockerfile. Defaults to \"/stage\"."
    )

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // The following keys are for generating dockerfiles; and staging, building, running, and
    // pushing images. These should not be overridden from the defaults unless you know what you're
    // doing.
    ////////////////////////////////////////////////////////////////////////////////////////////////

    val generateDockerfile: TaskKey[Unit] = taskKey[Unit](
      "Generates a Dockerfile for the main project image at the location pointed to by " +
        "`dockerfileLocation`."
    )

    val dockerDependencyStage: TaskKey[File] = taskKey[File](
      "Builds a staged directory under target/docker/dependencies containing project " +
        "dependencies. This will include a generated Dockerfile. This returns the staging " +
        "directory location."
    )

    val dockerMainStage: TaskKey[File] = taskKey[File](
      "Builds a staged directory under target/docker/main containing the staged project, minus " +
        "dependencies. If a Dockerfile is present in `dockerfileLocation.value`, it will be " +
        "placed in the staging directory. This returns the staging directory location."
    )

    val dockerBuild: TaskKey[String] = taskKey[String](
      "Builds a docker image for this project, returning the image ID. This requires that a " +
        "Dockerfile exist at `dockerfileLocation.value`."
    )

    val dockerRun: TaskKey[Unit] = taskKey[Unit](
      "Builds a docker image for this project, then runs it locally in a container."
    )

    val dockerKill: TaskKey[Unit] = taskKey[Unit](
      "Kills any currently-running docker container for this project."
    )
  }
  import autoImport._

  /** The default copy mapping, set to copy src/main/resources to conf in the docker image. */
  lazy val defaultCopyMappings = Def.setting {
    // TODO(jkinkead): Update this to use src/main/conf instead of src/main/resources, since the
    // `resources` directory is a special-use directory for files bundled into jars.
    Seq((new File(sourceMain.value, "resources"), "conf"))
  }

  /** The full image name, derived from the user-provided settings. */
  lazy val fullImageName: Def.Initialize[String] = Def.setting {
    if (dockerImageNamePrefix.value.nonEmpty) {
      dockerImageRegistryHost.value + '/' + dockerImageNamePrefix.value + '/' +
        dockerImageName.value
    } else {
      dockerImageRegistryHost.value + '/' + dockerImageName.value
    }
  }

  /** The full name of the dependency image. */
  lazy val dependencyImageName: Def.Initialize[String] = Def.setting {
    fullImageName.value + "-dependencies"
  }

  /** Task which requires that `docker` exists on the commandline path. */
  lazy val requireDocker: Def.Initialize[Task[Unit]] = Def.task {
    if (Process(Seq("which", "docker")).!(Utilities.NIL_PROCESS_LOGGER) != 0) {
      sys.error("`docker` not found on path. Please install the docker client before using this.")
    }
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Common settings used across tasks.
  //////////////////////////////////////////////////////////////////////////////////////////////////

  /** The src/main directory. This does not appear to be a setting key in sbt. */
  lazy val sourceMain: Def.Initialize[File] = Def.setting {
    new File(Keys.sourceDirectory.value, "main")
  }

  /** The location of the docker target directory containing all output for the docker plugin. */
  lazy val dockerTargetDir: Def.Initialize[File] = Def.setting {
    new File(Keys.target.value, "docker")
  }

  /** The location of the staged dependency image. */
  lazy val dependencyImageDir: Def.Initialize[File] = Def.setting {
    new File(dockerTargetDir.value, "dependencies")
  }

  /** The location of the staged main image. */
  lazy val mainImageDir: Def.Initialize[File] = Def.setting {
    new File(dockerTargetDir.value, "main")
  }

  /** The location of the staged dependency image's library directory, containing all staged jars.
    */
  lazy val dependencyLibDir: Def.Initialize[File] = Def.setting {
    new File(dependencyImageDir.value, "lib")
  }

  /** The location of the staged dependency image's Dockerfile. */
  lazy val dependencyDockerfile: Def.Initialize[File] = Def.setting {
    new File(dependencyImageDir.value, "Dockerfile")
  }

  /** The location of the staged dependency image's bin directory, containing the startup script. */
  lazy val dependencyImageBinDir: Def.Initialize[File] = Def.setting {
    new File(dependencyImageDir.value, "bin")
  }

  /** The location of the staged dependency image's startup script. */
  lazy val dependencyImageStartupScript: Def.Initialize[File] = Def.setting {
    new File(dependencyImageBinDir.value, STARTUP_SCRIPT_NAME)
  }

  /** The location of the staged dependency image's hash file, containing a hash of the image
    * contents.
    */
  lazy val dependencyHashFile: Def.Initialize[File] = Def.setting {
    new File(dockerTargetDir.value, "dependencies.sha1")
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////
  // Definitions for plugin tasks.
  //////////////////////////////////////////////////////////////////////////////////////////////////

  /** Task to build a dockerfile using the current project's sbt settings to populate it. */
  lazy val generateDockerfileDef: Def.Initialize[Task[Unit]] = Def.task {
    val logger = Keys.streams.value.log

    // Create the copy commands.
    val copyText = dockerCopyMappings.value.map {
      case (_, destination) => s"COPY $destination $destination"
    }.mkString("\n")
    // Create the sbt setting to recreate the copy mappings.
    val dockerCopyMappingsText = {
      // Generate the tuples.
      val tupleValues = dockerCopyMappings.value.map {
        case (file, destination) =>
          val basePath = Keys.baseDirectory.value.toPath
          // Relativize the file to the project root.
          val relativeFile = if (file.isAbsolute) {
            basePath.relativize(file.toPath).toFile
          } else {
            file
          }
          s"""(file("$relativeFile"), "$destination")"""
      }.mkString("#     ", ",\n#     ", "\n")
      // Turn into an sbt setting.
      "#   dockerCopyMappings := Seq(\n" + tupleValues + "#   )"
    }

    // Create the text for javaOptions & JVM_ARGS.
    val javaOptionsText = Keys.javaOptions.value.map('"' + _ + '"').mkString(", ")
    val jvmArgsText = Keys.javaOptions.value.mkString(" ")

    // Create the text for dockerPorts and EXPOSE commands.
    val dockerPortsText = dockerPorts.value.mkString(", ")
    val exposeText = dockerPorts.value.map("EXPOSE " + _).mkString("\n")

    // Check for a main class, and warn if it's missing.
    val (javaMainText, mainClassText) = Keys.mainClass.?.value.getOrElse(None) match {
      case Some(mainClass) =>
        (s"ENV JAVA_MAIN $${JAVA_MAIN:-$mainClass}", s"""#   mainClass := Some("$mainClass")""")
      case None =>
        logger.warn("No `mainClass` set! Your image will not run without manually setting " +
          "the JAVA_MAIN environment variable.")
        ("# (No mainClass set)", "#  mainClass := None")
    }

    // Create the text for dockerMainArgs and CMD command.
    val dockerMainArgsText = dockerMainArgs.value.map('"' + _ + '"').mkString(", ")

    val dockerfileContents = s"""# AUTOGENERATED
# Most lines in this file are derived from sbt settings. These settings are printed above the lines
# they affect.
#
# IMPORTANT: If you wish to make edits to this file, make changes BELOW the line starting with
# "#+#". Any updates to commands above this line should happen through sbt, and pushed to the
# Dockerfile using the `generateDockerfile` task.
#
# This image depends on the dependency image.
#
# The image name derives from:
#   dockerImageBase := "${dockerImageBase.value}"
FROM ${dependencyImageName.value}

# The ports which are available to map in the image.
# sbt setting:
#   dockerPorts := Seq[Int]($dockerPortsText)
$exposeText

# The variable determining which typesafe config file to use. You can override this with the -e
# flag:
#   docker run -e CONFIG_ENV=prod ${fullImageName.value}
# Note the default is "dev".
ENV CONFIG_ENV $${CONFIG_ENV:-dev}

# The arguments to send to the JVM. These can be overridden at runtime with the -e flag:
#   docker run -e JVM_ARGS="-Xms=1G -Xmx=1G" ${fullImageName.value}
#
# sbt setting:
#   javaOptions := Seq($javaOptionsText)
ENV JVM_ARGS $${JVM_ARGS:-$jvmArgsText}

# The main class to execute when using the ENTRYPOINT command. You can override this at runtime with
# the -e flag:
#   docker run -e JAVA_MAIN=org.allenai.HelloWorld ${fullImageName.value}
# sbt setting:
$mainClassText
$javaMainText

# The default arguments to use for running the image.
# See https://docs.docker.com/engine/reference/builder/#/understand-how-cmd-and-entrypoint-interact
# for detailed information on CMD vs ENTRYPOINT.
# sbt setting:
#   dockerMainArgs := Seq[String]($dockerMainArgsText)
CMD [$dockerMainArgsText]

# The script for this application to run. This can be overridden with the --entrypoint flag:
#   docker run --entrypoint /bin/bash ${fullImageName.value}
ENTRYPOINT ["bin/$STARTUP_SCRIPT_NAME"]

# The directories in the staging directory which will be mapping into the Docker image.
$dockerCopyMappingsText
$copyText

# lib is always copied, since it has the built jars.
COPY lib lib

# Any additions to the file below this line will be retained when `generateDockerfile` is run.
# Do not remove this line unless you want your changes overwritten!
$DOCKERFILE_SIGIL
"""

    // If there is already a Dockerfile, retain any contents past the sigil.
    val dockerfile = dockerfileLocation.value
    val existingContents = if (dockerfile.exists) {
      val lines = IO.readLines(dockerfile)
      val remainder = lines.dropWhile(_ != DOCKERFILE_SIGIL)
      if (remainder.nonEmpty) {
        remainder.tail.mkString("\n")
      } else {
        logger.warn(s"Overwriting Dockerfile at $dockerfile . . .")
        ""
      }
    } else {
      ""
    }

    IO.write(dockerfile, dockerfileContents + existingContents + "\n")
  }

  /** Task to stage a docker image containing the dependencies of the current project. This is used
    * to build a base image for the main project image.
    *
    * The result of this task is the directory containing the staged image. The directory will
    * contain a Dockerfile to build the image and a `lib` folder with all dependency jars.
    */
  lazy val dependencyStageDef: Def.Initialize[Task[File]] = Def.task {
    val logger = Keys.streams.value.log
    logger.info("Staging dependency image ...")

    // Create the destination directory.
    val imageDirectory = dependencyImageDir.value
    IO.createDirectory(imageDirectory)
    val lib = dependencyLibDir.value
    IO.createDirectory(lib)

    // Create the Dockerfile for the dependency image.
    val dockerfileContents = s"""
      |FROM ${dockerImageBase.value}
      |WORKDIR ${dockerWorkdir.value}
      |COPY bin bin
      |COPY lib lib
      |""".stripMargin
    IO.write(dependencyDockerfile.value, dockerfileContents)

    // Copy the startup script.
    val bin = dependencyImageBinDir.value
    if (!bin.exists) {
      IO.createDirectory(bin)
    }
    val startupScriptDestination = dependencyImageStartupScript.value
    Utilities.copyResourceToFile(getClass, STARTUP_SCRIPT_NAME, startupScriptDestination)
    startupScriptDestination.setExecutable(true)

    // Copy all of the library dependencies, saving the end location.
    val copiedFiles: Seq[File] = HelperDefs.remoteDependencies.value.map {
      case (file, destination) =>
        val destinationFile = new File(lib, destination)
        // Don't push bytes around unnecessarily. Note that this might leave stale snapshot
        // (dynamic) jars around long if they aren't named in a standard way.
        // A `clean` will wipe these out if needed.
        if (!destinationFile.exists || destinationFile.getName.contains("-SNAPSHOT")) {
          IO.copyFile(file, destinationFile)
        }
        destinationFile
    }

    // Remove any items in `lib` that are stale.
    val staleItems = lib.listFiles.toSet -- copiedFiles
    staleItems.foreach(_.delete())

    imageDirectory
  }

  /** Task to build a docker image containing the dependencies of the current project. This is used
    * as a base image for the main project image.
    */
  lazy val dependencyBuildDef: Def.Initialize[Task[Unit]] = Def.task {
    val logger = Keys.streams.value.log

    // This task requires docker to be installed.
    requireDocker.value

    // This task requires that the docker dependency stage have been run.
    dockerDependencyStage.value

    // Calculate the checksum of the Dockerfile and dependency files.
    val libFiles: Array[File] = Option(dependencyLibDir.value.listFiles).getOrElse(Array.empty)
    val dockerfile = dependencyDockerfile.value
    val dependencyHash = Utilities.hashFiles(libFiles :+ dockerfile, dependencyImageDir.value)

    // Check to see if the dependency contents have changed since the last time we sent them to
    // docker.
    val hashFile = dependencyHashFile.value
    val oldDependencyHash = if (hashFile.exists) {
      IO.read(hashFile)
    } else {
      ""
    }

    if (dependencyHash != oldDependencyHash) {
      // Remove any old image, and delete the new image. Note that we ignore any errors, since this
      // image might not exist.
      val rmExitCode = Process(Seq("docker", "rmi", dependencyImageName.value)).!
      if (rmExitCode == 0) {
        logger.info("Removed stale image.")
      } else {
        logger.info("No stale image to remove.")
      }
      // Build a new docker image.
      val dockerCommand = Seq(
        "docker",
        "build",
        "-t", dependencyImageName.value,
        dependencyImageDir.value.toString
      )
      logger.info("Building dependency image . . .")
      val exitCode = Process(dockerCommand).!
      if (exitCode != 0) {
        sys.error("Error running " + dockerCommand.mkString(" "))
      }

      // Write out the hash file.
      IO.write(hashFile, dependencyHash)
    } else {
      logger.info("Dependency image unchanged.")
    }
  }

  /** Task to stage the main docker image for the project. */
  lazy val mainImageStageDef: Def.Initialize[Task[File]] = Def.task {
    val logger = Keys.streams.value.log

    val dockerfile = dockerfileLocation.value
    if (!dockerfile.exists) {
      sys.error(s"No Dockerfile found at $dockerfile .\n" +
        "Maybe you should generate one with the `generateDockerfile` task?")
    }

    logger.info("Staging main image ...")

    // Create the destination directory.
    val imageDirectory = mainImageDir.value
    IO.createDirectory(imageDirectory)

    // Create the destination for libraries.
    val lib = new File(imageDirectory, "lib")
    if (!lib.exists) {
      IO.createDirectory(lib)
    }

    // Copy the Dockerfile.
    IO.copyFile(dockerfile, new File(imageDirectory, "Dockerfile"))
    // Copy the mappings.
    dockerCopyMappings.value.foreach {
      case (maybeRelativeSource, relativeDestination) =>
        // Make any relative path relative to the base directory.
        val source = if (maybeRelativeSource.isAbsolute) {
          maybeRelativeSource
        } else {
          new File(Keys.baseDirectory.value, maybeRelativeSource.toString)
        }
        val destination = new File(imageDirectory, relativeDestination)
        if (source.exists) {
          if (source.isDirectory) {
            IO.createDirectory(destination)
            IO.copyDirectory(source, destination)
          } else {
            IO.copyFile(source, destination)
          }
        } else {
          // The Dockerfile command COPY will error if the source doesn't exist, and Dockerfile
          // generation can't see what'll exist in the staged directory, so create a dummy file if
          // there's no source to copy from.
          IO.write(destination, "(dummy)")
        }
    }

    // Copy the local project jars.
    HelperDefs.localDependencies.value.foreach {
      case (file, destination) =>
        val destinationFile = new File(lib, destination)
        IO.copyFile(file, destinationFile)
    }

    // TODO(jkinkead): If the git repository is clean, create a sha1 cache key file.

    imageDirectory
  }

  /** Task to build the main docker image for the project. This returns the image ID. */
  lazy val mainImageBuildDef: Def.Initialize[Task[String]] = Def.task {
    val logger = Keys.streams.value.log

    // This task requires docker to be installed.
    requireDocker.value

    // This task requires that the dependency image be created, and that the main image be staged.
    mainImageStageDef.value
    dependencyBuildDef.value

    // Build a new docker image.
    val dockerCommand = Seq(
      "docker",
      "build",
      "-t", fullImageName.value,
      mainImageDir.value.toString
    )
    logger.info("Building main image . . .")
    val exitCode = Process(dockerCommand).!
    if (exitCode != 0) {
      sys.error("Error running " + dockerCommand.mkString(" "))
    }

    fullImageName.value
  }

  /** Adds the settings to configure the `dockerBuild` command. */
  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    dockerfileLocation := {
      new File(sourceMain.value, "docker" + File.separatorChar + "Dockerfile")
    },
    dockerCopyMappings := defaultCopyMappings.value,
    dockerImageRegistryHost := AI2_PRIVATE_REGISTRY,
    dockerImageNamePrefix := Keys.organization.value.stripPrefix("org.allenai."),
    dockerImageName := Keys.name.value,
    dockerImageBase := DEFAULT_BASE_IMAGE,
    dockerPorts := Seq.empty,
    dockerMainArgs := Seq.empty,
    dockerWorkdir := "/stage",
    generateDockerfile := generateDockerfileDef.value,
    dockerDependencyStage := dependencyStageDef.value,
    dockerMainStage := mainImageStageDef.value,
    dockerBuild := mainImageBuildDef.value
  )
}
