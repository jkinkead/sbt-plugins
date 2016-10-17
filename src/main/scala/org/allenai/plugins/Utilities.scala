package org.allenai.plugins

import sbt.{ inProjects, Def, ScopeFilter, Task }
import sbt.Keys.{ buildDependencies, thisProjectRef }

/** Helper methods and values for building plugins. */
object Utilities {
  /** A filter for local project dependencies. This filter can be used to aggregate the values of
    * keys for local subprojects the current project depends on. This is useful to do things like
    * locate all of the local jars your project depends on.
    */
  lazy val localDependencyFilter: Def.Initialize[Task[ScopeFilter]] = Def.task {
    val localDependencies = buildDependencies.value.classpathTransitiveRefs(thisProjectRef.value)
    ScopeFilter(inProjects(localDependencies: _*))
  }
}
