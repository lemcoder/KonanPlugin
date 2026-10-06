package io.github.lemcoder.jni

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ExternalStubVariable
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/**
 * [JniStubBuild.External.CMake]: configure, then build, the project's CMakeLists for one target. The
 * contract also goes in as cache entries — what a CMakeLists conventionally reads — and
 * `CMAKE_LIBRARY_OUTPUT_DIRECTORY` lands the library where the plugin says, so the CMakeLists needs no
 * knowledge of the layout.
 *
 * Both commands are one task, which always runs: CMake tracks its own sources, and a changed configure
 * argument then always reaches the build.
 */
internal fun addCMakeCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) {
    val cmake = binding.jni.cmake
    val perTarget = cmake.targetSettings[context.target]
    val preset = perTarget?.preset?.orElse(cmake.preset) ?: cmake.preset
    check(cmake.path.isPresent || preset.isPresent) { "konan.bindings.${binding.name}.jni.cmake needs a path or a preset." }
    val sourceDirectory = cmake.path.map { it.asFile.parentFile }
    // With a preset the binary directory is the preset's to choose, and `-B` cannot override it; the
    // conventional layout is <source>/build/<preset>, which stays overridable.
    val buildDirectory: Provider<Directory> = (perTarget?.buildDirectory ?: cmake.buildDirectory).orElse(
        project.layout.dir(
            preset.map { presetName -> sourceDirectory.get().resolve("build/$presetName") }
                .orElse(project.layout.buildDirectory.dir("konan/bindings/${binding.name}/jni/cmake/${context.target.konanName}").map { it.asFile })
        )
    )

    task.command {
        executable.set(cmake.executable)
        arguments.addAll(
            preset.map { listOf("--preset", it) }.orElse(
                sourceDirectory.zip(buildDirectory) { source, build -> listOf("-S", source.absolutePath, "-B", build.asFile.absolutePath) }
            )
        )
        listOf(ExternalStubVariable.STUB_DIR, ExternalStubVariable.LIB_NAME).forEach { variable ->
            arguments.add(context[variable].map { "-D${variable.variableName}=$it" })
        }
        arguments.add(context[ExternalStubVariable.OUTPUT_DIR].map { "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$it" })
        arguments.addAll(cmake.arguments)
        perTarget?.let { arguments.addAll(it.arguments) }
        // `--preset` resolves CMakePresets.json from the working directory.
        workingDirectory.set(sourceDirectory.map { it.absolutePath })
    }
    task.command {
        executable.set(cmake.executable)
        arguments.addAll(buildDirectory.map { listOf("--build", it.asFile.absolutePath) })
        arguments.addAll(cmake.buildTargets.map { targets -> targets.flatMap { listOf("--target", it) } })
        arguments.add("--parallel")
    }
}
