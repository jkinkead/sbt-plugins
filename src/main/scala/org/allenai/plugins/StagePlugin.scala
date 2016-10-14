package org.allenai.plugins

import sbt.{ AutoPlugin, Plugins }
import sbt.plugins.JvmPlugin

/** Plugin to add a `stage` command mimicking the behavior in the sbt-native-packager
  * (https://github.com/sbt/sbt-native-packager).  This is meant to be a simpler,
  * more-understandable replacement, and to provide a cleaner Docker integration point.
  *
  * This adds a `stage` task which builds a binary for the current project.
  */
object StagePlugin extends AutoPlugin {
  override def requires: Plugins = JvmPlugin

  object autoImport {

  }

  /** Task which finds all jars */

  // HACKS FOR SBT HACKING
  // NOTES:
  // - MappingsHelper builds up the copying rules for the universal packager
  // - archetypes/JavaApp contains logic for building good jar names
  // - scriptClasspathOrdering contains logic for building up an ordered
  //   classpath (probably matching that of `sbt run`)
  lazy val printDeps = taskKey[Seq[File]]("printDeps")
  lazy val printDepsSetting = printDeps := Def.taskDyn {
    // The runtime classpath includes all jars needed to run, as well as the target directories for
    // local dependencies (and the current project).
    val allDeps = fullClasspath.in(Runtime).value
    // Filter out dependencies that don't have an artifact to include.
    val depsWithArtifacts = allDeps.filter { attributedFile =>
      attributedFile.get(Keys.artifact.key).nonEmpty
    }
    // Map to the actual files.
    val allDepsFiles = depsWithArtifacts.map(_.data)

    val buildDependenciesValue: BuildDependencies = buildDependencies.value
    val thisProject: ProjectRef = thisProjectRef.value
    // All projects the current project depends on.
    val dependencyProjects: Seq[ProjectRef] =
      buildDependenciesValue.classpathTransitive.get(thisProject).getOrElse(Seq.empty)

    val allProjects: Seq[ProjectRef] = thisProject +: dependencyProjects

    // Build up the task def to find all artifacts.
    val allRuntimeArtifacts: Def.Initialize[Task[Seq[File]]] = {
      state.apply { stateTask: Task[State] =>
        // Create tasks to look up all artifacts for each project.
        val allArtifactTasks: Seq[Task[File]] = allProjects.map { projectRef =>
          stateTask.flatMap { stateValue =>
            val extracted = Project.extract(stateValue)
            val module = extracted.get(projectID.in(projectRef))
            extracted.get(packageBin.in(Compile).in(projectRef))
          }
        }
        // Collapse the tasks into a single task.
        allArtifactTasks.foldLeft(task[Seq[File]](Seq.empty)) { (aggTask, currTask) =>
          currTask.flatMap { currArtifact: File =>
            aggTask.map { aggArtifacts: Seq[File] =>
              aggArtifacts :+ currArtifact
            }
          }
        }
      }
    }
    allRuntimeArtifacts
  }.value
}
