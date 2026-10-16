package io.github.lemcoder.dsl.libraries

import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * What a library provider guarantees and every consumer relies on: one static archive per target, at
 * [archive], once [builtBy] has run. Consumers — cinterop, the JNI and FFM links — see only this, never
 * which provider made the archives or how.
 */
internal class NativeArtifacts(
    /** The library's name; the archives are `lib<name>.a`. */
    val name: String,
    private val outputDirectory: Provider<Directory>,
    val targets: Provider<List<NativeTarget>>,
    /** Depend on this, not on a provider's task: it stands for "the archives exist". */
    val builtBy: TaskProvider<*>,
) {
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
            builtBy = project.tasks.named(taskName(library.name)),
        )
    }
}
