package org.allenai.plugins

import sbt.{
  rebase,
  relativeTo,
  task,
  taskKey,
  Artifact,
  AttributeKey,
  AutoPlugin,
  BuildDependencies,
  Compile,
  Def,
  Extracted,
  File,
  Hash,
  IO,
  Keys,
  ModuleID,
  PathFinder,
  Plugins,
  Project,
  ProjectRef,
  Runtime,
  State,
  Task,
  TaskKey
}
import sbt.plugins.JvmPlugin

import scala.collection.mutable

/** Plugin to add a `allenai:stage` command mimicking the behavior in the sbt-native-packager
  * (https://github.com/sbt/sbt-native-packager).  This is meant to be a simpler,
  * more-understandable replacement, and to provide a cleaner Docker integration point.
  *
  * By default, `src/main/resources` will be copied into `staging/conf`, and `src/main/bin` will be
  * copied into `staging/bin`. This can be changed by updating the `allenai:mappings` setting.
  */
object StagePlugin extends AutoPlugin {
  override def requires: Plugins = JvmPlugin

  object autoImport {
    val AllenAi = Utilities.AllenAi

    val stage = taskKey[File]("Builds and stages the project for deploy")
  }

  /** Task initializer to look up local artifacts for the current project. This is the jar built
    * from the current sbt project along with any jars from local subprojects the current project
    * depends on.
    *
    * This task result contains the pairing of built jars with their target filenames.
    *
    * Side-effect: Binary build (`packageBin`) for all project dependencies.
    */
  lazy val localDependenciesDef: Def.Initialize[Task[Seq[(File, String)]]] = Def.taskDyn {
    // The current project & its dependencies.
    val allProjects: Seq[ProjectRef] = {
      val thisProject: ProjectRef = Keys.thisProjectRef.value
      // All projects the current project depends on.
      val dependencyProjects: Seq[ProjectRef] = {
        val buildDependenciesValue: BuildDependencies = Keys.buildDependencies.value
        buildDependenciesValue.classpathTransitive.get(thisProject).getOrElse(Seq.empty)
      }
      thisProject +: dependencyProjects
    }

    Keys.state.apply { stateTask: Task[State] =>
      // Create tasks to look up all artifacts for each project.
      val allArtifactTasks: Seq[Task[(File, String)]] = allProjects.map { projectRef =>
        stateTask.flatMap { stateValue =>
          val extracted: Extracted = Project.extract(stateValue)
          val jarName: String = {
            val moduleId: ModuleID = extracted.get(Keys.projectID.in(projectRef))
            val artifact: Artifact = extracted.get(Keys.artifact.in(projectRef))
            Utilities.jarName(moduleId, artifact)
          }
          val binaryTask: Task[File] =
            extracted.get(Keys.packageBin.in(Compile).in(projectRef))
          binaryTask.map { binary =>
            (binary, jarName)
          }
        }
      }
      // Collapse the tasks into a single task.
      allArtifactTasks.foldLeft(task[Seq[(File, String)]](Seq.empty)) { (aggTask, currTask) =>
        currTask.flatMap { currPair: (File, String) =>
          aggTask.map { aggPairs: Seq[(File, String)] => aggPairs :+ currPair }
        }
      }
    }
  }

  /** Task initializer to look up non-local dependency artifacts for the current project. This
    * contains all direct and transitive dependencies pulled from remote sources.
    *
    * This task result contains the pairing of dependency jars with their target filenames.
    */
  lazy val remoteDependenciesDef: Def.Initialize[Task[Seq[(File, String)]]] = Def.task {
    // The runtime classpath includes all jars needed to run, as well as the target directories for
    // local dependencies (and the current project).
    val allDependencies = Keys.fullClasspath.in(Runtime).value
    // Filter out dependencies that don't have an artifact to include and those that aren't files
    // (the directory targets).
    val jarDependencies = allDependencies.filter { dependency =>
      dependency.get(Keys.artifact.key).nonEmpty && dependency.data.isFile
    }

    // Map to the file / rename pairings.
    jarDependencies.map { dependency =>
      val file = dependency.data
      val jarName = {
        val moduleIdOption = dependency.metadata.get(AttributeKey[ModuleID]("module-id"))
        val artifactOption = dependency.metadata.get(AttributeKey[Artifact]("artifact"))
        // Try to get the name from the artifact data; else, use the filename.
        val generatedNameOption = moduleIdOption.zip(artifactOption).headOption.map {
          case (moduleId, artifact) => Utilities.jarName(moduleId, artifact)
        }
        generatedNameOption.getOrElse(file.getName)
      }
      (file, jarName)
    }
  }

  /** Build the project and copy all files into the staging directory. */
  lazy val stageTask: Def.Initialize[Task[File]] = Def.task {
    // Create the destination directory.
    val stageDir = new File(Keys.target.value, "stage")
    IO.createDirectory(stageDir)
    val stagePath = stageDir.toPath
    val libPath = stagePath.resolve("lib")

    // Copy all of the library dependencies.
    val allLibraries = (localDependenciesDef.value ++ remoteDependenciesDef.value)
    allLibraries.foreach {
      case (file, destination) =>
        val destinationFile = libPath.resolve(destination).toFile
        IO.copyFile(file, destinationFile)
    }

    // Copy all of the other files as directed.
    Keys.mappings.in(autoImport.AllenAi).value.foreach {
      case (file, destination) =>
        val destinationFile = stagePath.resolve(destination).toFile
        Keys.streams.value.log.info(s"Considering copying $file to $destinationFile")
        if (file.isFile) {
          IO.copyFile(file, destinationFile)
        }
    }

    // TODO: Copy run-class.sh and run-docker.sh.

    // TODO: Build up a cache hash.

    stageDir
  }

  /** Extra mappings to add to `mappings.in(AllenAi)`. By default, this maps `src/main/resources` to
    * `conf` as well as `src/main/bin` to `bin`.
    */
  lazy val extraMappingsTask: Def.Initialize[Task[Seq[(File, String)]]] = Def.task {
    val sourceMain = Keys.sourceDirectory.value.toPath.resolve("main")
    // See http://www.scala-sbt.org/0.12.3/docs/Detailed-Topics/Mapping-Files.html
    // for more info on sbt mappings.
    val resourcesToConf = PathFinder(sourceMain.resolve("resources").toFile).***
      .pair(rebase(sourceMain.resolve("resources").toFile, "conf"))

    val binToBin = PathFinder(sourceMain.resolve("bin").toFile).***
      .pair(relativeTo(sourceMain.toFile))

    (resourcesToConf ++ binToBin).map { v =>
      Keys.streams.value.log.info(s"found $v")
      v
    }
  }

  // NOTES:
  // - MappingsHelper builds up the copying rules for the universal packager
  // - scriptClasspathOrdering contains logic for building up an ordered
  //   classpath (probably matching that of `sbt run`)

  /** Adds the settings to configure the `allenai:stage` command. */
  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    autoImport.stage.in(autoImport.AllenAi) := stageTask.value,
    Keys.mappings.in(autoImport.AllenAi) := extraMappingsTask.value
  )
}
