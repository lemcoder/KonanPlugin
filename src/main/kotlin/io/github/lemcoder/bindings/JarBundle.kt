package io.github.lemcoder.bindings

import io.github.lemcoder.model.BundledLibraries
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.util.cleanTask
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider

/**
 * A resources root holding [libraries] as `konan/natives/<target>/<file>` — where the generated bindings'
 * loader finds them when `java.library.path` does not have them — built after [builtBy].
 */
internal fun Project.registerJarBundle(
    taskName: String,
    taskDescription: String,
    libraries: Map<NativeTarget, Provider<RegularFile>>,
    destination: File,
    builtBy: Any,
): TaskProvider<Sync> {
    val clean = cleanTask()
    return tasks.register(taskName, Sync::class.java) {
        group = "konan"
        description = taskDescription
        dependsOn(builtBy)
        mustRunAfter(clean)
        libraries.forEach { (target, library) ->
            from(library) { into("${BundledLibraries.RESOURCE_ROOT}/${target.konanName}") }
        }
        into(destination)
    }
}
