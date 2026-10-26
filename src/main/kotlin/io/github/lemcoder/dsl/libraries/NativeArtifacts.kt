package io.github.lemcoder.dsl.libraries

import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * What a library's build guarantees and every consumer relies on: one static archive per target, at
 * [archive], once [builtBy] that target has run. Consumers — cinterop, the JNI and FFM links — see only
 * this, never which build made the archives or how.
 */
internal class NativeArtifacts(
    /** The library's name; the archives are `lib<name>.a`. */
    val name: String,
    private val outputDirectory: Provider<Directory>,
    val targets: Provider<List<NativeTarget>>,
    /** `compileNativeLibrary<Name>`: every target's archive. */
    val builtByAll: TaskProvider<*>,
    private val buildsByTarget: Map<NativeTarget, TaskProvider<*>>,
    private val project: Project,
) {
    /**
     * What makes [target]'s archive: depend on this for one target, not on [builtByAll], or building one
     * target builds them all (#9). Read lazily, when the task graph is built: by then every library's
     * build is registered, whichever order the script declared them in.
     */
    fun builtBy(target: NativeTarget): Provider<List<TaskProvider<*>>> =
        project.provider { listOf(buildsByTarget[target] ?: builtByAll) }

    fun archive(target: NativeTarget): Provider<RegularFile> = outputDirectory.map { it.file(archivePath(name, target)) }

    fun directory(target: NativeTarget): Provider<Directory> = outputDirectory.map { it.dir(target.konanName) }

    companion object {
        /** The one place the layout is written: `<target>/lib<name>.a` under a library's output directory. */
        fun archivePath(name: String, target: NativeTarget): String = "${target.konanName}/${archiveFileName(name)}"

        fun archiveFileName(name: String): String = "lib$name.a"

        /** The lifecycle task every library has, whatever its provider. */
        fun taskName(name: String): String = taskName("compileNativeLibrary", name)

        fun of(project: Project, library: NativeLibrarySpec) = NativeArtifacts(
            name = library.name,
            outputDirectory = library.outputDirectory,
            targets = library.targets,
            builtByAll = project.tasks.named(taskName(library.name)),
            buildsByTarget = library.buildsByTarget,
            project = project,
        )
    }
}
