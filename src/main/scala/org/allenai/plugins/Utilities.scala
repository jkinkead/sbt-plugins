package org.allenai.plugins

import sbt.{
  Artifact,
  AttributeKey,
  Attributed,
  ConfigKey,
  Def,
  File,
  ModuleID,
  Keys
}

/** Helper methods and values for building plugins. */
object Utilities {
  /** Configuration key for AI2 items. */
  val AllenAi = ConfigKey("allenai")

  /** Construct a unique jar name from the given module and artifact data. This will be of the form:
    * {{{
    * "${module.organization}.${module.name}-${artifact.name}-" +
    *   "${module.revision}-${artifact.classifier}.jar"
    * }}}
    * Classifier will be dropped if it's unset. The artifact name will be dropped if it exactly
    * matches the module name, and will have the module name stripped out regardless.
    */
  def jarName(module: ModuleID, artifact: Artifact): String = {
    val jarName = new StringBuilder(module.organization).append('.').append(module.name).append('-')

    // Elide the artifact name if it exactly matches the module name.
    if (module.name != artifact.name && artifact.name.nonEmpty) {
      // Replace any occurance of the module name, to remove redundancy.
      val strippedArtifactName = artifact.name.replace(module.name, "")
      jarName.append(strippedArtifactName).append('-')
    }

    jarName.append(module.revision)

    if (artifact.classifier.nonEmpty && artifact.classifier.get.nonEmpty) {
      jarName.append('-').append(artifact.classifier.get)
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
      case (moduleId, artifact) => jarName(moduleId, artifact)
    }
    // Fall back on the embedded name.
    generatedNameOption.getOrElse(file.data.getName)
  }
}
