package io.github.lemcoder.library

import io.github.lemcoder.dsl.NativeLibrarySpec
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.toolchain.zigTriple
import org.gradle.api.Project
import org.jetbrains.kotlin.konan.target.Family

/**
 * [LibraryProvider.External.Zig]: `zig build` for one target, with `-Dtarget` set to the Zig triple that
 * matches Kotlin/Native's sysroot, installed into a prefix of its own; its static library is then copied
 * to where every other provider puts the archive.
 */
internal fun addZigCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, contract: LibraryContract) {
    val zig = library.zig
    val target = contract.target
    // Zig's prefix and cache stay out of the project directory, which is the task's input.
    val work = project.layout.buildDirectory.dir("konan/libraries/${library.name}/zig")
    val prefix = work.map { it.dir(target.name) }

    task.command {
        executable.set(zig.executable)
        arguments.addAll(prefix.map { listOf("build", "--prefix", it.asFile.absolutePath) })
        arguments.addAll(work.map { listOf("--cache-dir", it.dir("cache").asFile.absolutePath) })
        arguments.add("-Dtarget=${target.zigTriple()}")
        arguments.add(zig.optimize.map { "-Doptimize=${it.zigName}" })
        arguments.addAll(zig.arguments)
        workingDirectory.set(zig.projectDirectory.map { it.asFile.absolutePath })
    }
    task.sources.from(zig.projectDirectory.map { directory -> directory.asFileTree.matching { exclude(".zig-cache/**", "zig-out/**") } })
    // Zig names the archive after the target's convention: libfoo.a, or foo.lib on Windows.
    task.producedLibrary.set(
        prefix.zip(zig.artifactName) { directory, artifact ->
            directory.file(if (target.family == Family.MINGW) "lib/$artifact.lib" else "lib/lib$artifact.a")
        }
    )
}
