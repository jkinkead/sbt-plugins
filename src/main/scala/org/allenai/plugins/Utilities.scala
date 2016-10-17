package org.allenai.plugins

import sbt.{ inProjects, Artifact, Def, ModuleID, Keys, ScopeFilter, Task }

/** Helper methods and values for building plugins. */
object Utilities {
  /** A filter for local project dependencies. This filter can be used to aggregate the values of
    * keys for local subprojects the current project depends on. This is useful to do things like
    * locate all of the local jars your project depends on.
    */
  lazy val localDependencyFilter: Def.Initialize[Task[ScopeFilter]] = Def.task {
    val localDependencies =
      Keys.buildDependencies.value.classpathTransitiveRefs(Keys.thisProjectRef.value)
    ScopeFilter(inProjects(localDependencies: _*))
  }

  /** Construct a unique jar name from a given module ID and artifact. This will be of the form:
    * {{{
    * "${module.organization}.${module.name}-" +
    *   "${artifact.name}-${module.revision}-${artifact.classifier}".jar
    * }}}
    * Classifier will be dropped if it's unset. The artifact name will be dropped if it exactly
    * matches the module name, and will have the module name stripped out regardless.
    */
  def buildJarName(module: ModuleID, artifact: Artifact): String = {
    val jarName = new StringBuilder(module.organization).append('.').append(module.name)

    // Elide the artifact name if it exactly matches the module name.
    if (module.name != artifact.name && artifact.name.nonEmpty) {
      // Replace any occurance of the module name, to remove redundancy.
      val strippedArtifactName = artifact.name.replace(module.name, "")
      jarName.append('-').append(strippedArtifactName)
    }

    jarName.append('-').append(module.revision)
    if (artifact.classifier.nonEmpty) {
      jarName.append('-').append(artifact.classifier)
    }

    jarName.append(".jar").toString
  }
}
