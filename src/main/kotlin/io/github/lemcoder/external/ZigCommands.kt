package io.github.lemcoder.external

import io.github.lemcoder.dsl.builds.ZigSettings
import io.github.lemcoder.dsl.config.konanConfig
import io.github.lemcoder.model.ConfigField
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.TargetFamily
import io.github.lemcoder.toolchain.zigTriple
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * [ExternalBuild.Zig]: `zig build` for one target, with `-Dtarget` set to the Zig triple that matches
 * Kotlin/Native's sysroot, installed into a prefix under [work]; its static library is then copied to
 * where every other build puts the archive.
 */
internal fun ExternalBuildTask.addZigCommands(zig: ZigSettings, work: Provider<Directory>, context: ExternalBuildContext) {
    val target = context.target
    // Zig's prefix and cache stay out of the project directory, which is the task's input.
    val prefix = work.map { it.dir(target.konanName) }

    val zigExecutable = project.konanConfig.executable(ConfigField.ZIG, path)
    command {
        executable.set(zigExecutable)
        arguments.addAll(prefix.map { listOf("build", "--prefix", it.asFile.absolutePath) })
        arguments.addAll(work.map { listOf("--cache-dir", it.dir("cache").asFile.absolutePath) })
        arguments.add("-Dtarget=${target.zigTriple()}")
        arguments.add(zig.optimize.map { "-Doptimize=${it.zigName}" })
        arguments.addAll(zig.arguments)
        workingDirectory.set(zig.projectDirectory.map { it.asFile.absolutePath })
    }
    sources.from(zig.projectDirectory.map { directory -> directory.asFileTree.matching { exclude(".zig-cache/**", "zig-out/**") } })
    // Zig names the archive after the target's convention: libfoo.a, or foo.lib on Windows.
    producedLibrary.set(
        prefix.zip(zig.artifactName) { directory, artifact ->
            directory.file(if (target.family == TargetFamily.MINGW) "lib/$artifact.lib" else "lib/lib$artifact.a")
        }
    )
}
