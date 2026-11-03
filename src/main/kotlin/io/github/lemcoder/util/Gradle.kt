package io.github.lemcoder.util

import org.gradle.api.Project

/**
 * The `clean` task as a lazy collection — empty when the base plugin isn't applied, so it is safe to
 * pass to `mustRunAfter` unconditionally.
 */
internal fun Project.cleanTask() = tasks.matching { it.name == "clean" }
