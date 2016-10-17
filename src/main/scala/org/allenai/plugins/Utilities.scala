package org.allenai.plugins

import sbt.{
  inProjects,
  Artifact,
  AttributeKey,
  Attributed,
  Def,
  File,
  ModuleID,
  Keys,
  ScopeFilter,
  Task
}

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

  /** Construct a unique jar name from the given module and artifact data. This will be of the form:
    * {{{
    * "${organization}.${moduleName}-${artifactName}-${revision}-${classifier}".jar
    * }}}
    * Classifier will be dropped if it's unset. The artifact name will be dropped if it exactly
    * matches the module name, and will have the module name stripped out regardless.
    */
  def jarName(
    organization: String,
    moduleName: String,
    artifactName: String,
    revision: String,
    classifier: Option[String]
  ): String = {
    val jarName = new StringBuilder(organization).append('.').append(moduleName).append('-')

    // Elide the artifact name if it exactly matches the module name.
    if (moduleName != artifactName && artifactName.nonEmpty) {
      // Replace any occurance of the module name, to remove redundancy.
      val strippedArtifactName = artifactName.replace(moduleName, "")
      jarName.append(strippedArtifactName).append('-')
    }

    jarName.append(revision)

    if (classifier.nonEmpty) {
      jarName.append('-').append(classifier)
    }

    jarName.append(".jar").toString
  }

  /** Return the jar name for a given dependency file.
    * @param file the dependency file with attributes describing module and artifact
    * @return the descriptive filename the dependency jar can go into
    */
  def jarNameForFile(file: Attributed[File]): String = {
    val moduleIdOption = file.metadata.get(AttributeKey[ModuleID]("module-id"))
    val artifactOption = file.metadata.get(AttributeKey[Artifact]("artifact"))
    val generatedNameOption = moduleIdOption.zip(artifactOption).headOption.map {
      case (moduleId, artifact) =>
        jarName(
          organization = moduleId.organization,
          moduleName = moduleId.name,
          artifactName = artifact.name,
          revision = moduleId.revision,
          classifier = artifact.classifier
        )
    }
    // Fall back on the embedded name.
    generatedNameOption.getOrElse(file.data.getName)
  }
}
