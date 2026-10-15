package io.github.lemcoder.external

import io.github.lemcoder.dsl.builds.CMakeSettings
import io.github.lemcoder.dsl.config.konanConfig
import io.github.lemcoder.model.ConfigField
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ToolchainVariable
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * The CMake preset of an external build: configure, then build, the project's CMakeLists for one target.
 * The contract also goes in as cache entries — what a CMakeLists conventionally reads — and
 * `CMAKE_LIBRARY_OUTPUT_DIRECTORY` and `CMAKE_ARCHIVE_OUTPUT_DIRECTORY` land the library in the context's
 * output directory, so the CMakeLists needs no knowledge of the layout. Used for a library's archive, the
 * JNI stub and the library FFM loads.
 *
 * Both commands are one task, which always runs: CMake tracks its own sources, and a changed configure
 * argument then always reaches the build.
 *
 * @param owner the DSL path, for messages, e.g. `konan.bindings.mymath.jni.cmake`.
 * @param defaultBuildRoot where builds go without a preset or a build directory: `<root>/<target>`.
 */
internal fun ExternalBuildTask.addCMakeCommands(
    project: Project,
    cmake: CMakeSettings,
    context: ExternalBuildContext,
    owner: String,
    defaultBuildRoot: Provider<Directory>,
) {
    val perTarget = cmake.targetSettings[context.target]
    val preset = perTarget?.preset?.orElse(cmake.preset) ?: cmake.preset
    // Read before command { }, whose receiver has neither the task's path nor its project.
    val cmakeExecutable = project.konanConfig.executable(ConfigField.CMAKE, path)
    check(cmake.path.isPresent || preset.isPresent) { "$owner needs a path or a preset." }
    val sourceDirectory = cmake.path.map { it.asFile.parentFile }
    // With a preset the binary directory is the preset's to choose, and `-B` cannot override it; the
    // conventional layout is <source>/build/<preset>, which stays overridable.
    val buildDirectory: Provider<Directory> = (perTarget?.buildDirectory ?: cmake.buildDirectory).orElse(
        project.layout.dir(
            preset.map { presetName -> sourceDirectory.get().resolve("build/$presetName") }
                .orElse(defaultBuildRoot.map { it.dir(context.target.konanName).asFile })
        )
    )

    command {
        executable.set(cmakeExecutable)
        arguments.addAll(
            preset.map { listOf("--preset", it) }.orElse(
                sourceDirectory.zip(buildDirectory) { source, build -> listOf("-S", source.absolutePath, "-B", build.asFile.absolutePath) }
            )
        )
        // Kotlin/Native's compilers by toolchain file — the arguments after it can still name another, and
        // a preset names its own.
        if (!preset.isPresent) {
            context.toolchain?.let { toolchain -> arguments.add(toolchain.cmakeToolchainFile.map { "-DCMAKE_TOOLCHAIN_FILE=${it.absolutePath}" }) }
        }
        context.environment.filterKeys { it !is ToolchainVariable }
            .forEach { (variable, value) -> arguments.add(value.map { "-D${variable.variableName}=$it" }) }
        // Shared libraries go to the library directory, static ones to the archive directory: both land in place.
        arguments.add(context.outputDirectory.map { "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${it.asFile.absolutePath}" })
        arguments.add(context.outputDirectory.map { "-DCMAKE_ARCHIVE_OUTPUT_DIRECTORY=${it.asFile.absolutePath}" })
        arguments.addAll(cmake.arguments)
        perTarget?.let { arguments.addAll(it.arguments) }
        // `--preset` resolves CMakePresets.json from the working directory.
        workingDirectory.set(sourceDirectory.map { it.absolutePath })
    }
    command {
        executable.set(cmakeExecutable)
        arguments.addAll(buildDirectory.map { listOf("--build", it.asFile.absolutePath) })
        arguments.addAll(cmake.buildTargets.map { targets -> targets.flatMap { listOf("--target", it) } })
        arguments.add("--parallel")
    }
}
