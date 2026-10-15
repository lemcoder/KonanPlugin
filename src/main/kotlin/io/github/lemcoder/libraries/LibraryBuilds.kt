package io.github.lemcoder.libraries

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.builds.ExternalBuildSettings
import io.github.lemcoder.dsl.builds.PrebuiltSettings
import io.github.lemcoder.dsl.builds.blockName
import io.github.lemcoder.dsl.libraries.NativeLibrarySpec
import io.github.lemcoder.external.ExternalBuild
import io.github.lemcoder.external.ExternalBuildOutput
import io.github.lemcoder.model.ExternalVariable
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.selectOne
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Registers the work that builds [library]'s archives, however its script said: compiled with
 * Kotlin/Native's clang by default, placed from `prebuilt { }`, or built by the project's own build. Each
 * leaves one archive per target at [NativeArtifacts.archivePath] under the library's output directory —
 * the only thing consumers know. Called once the build script has run.
 */
internal fun Project.registerLibraryBuild(konan: KonanExtension, library: NativeLibrarySpec): List<TaskProvider<*>> =
    when (val settings = selectOne("konan.libraries.${library.name}", library.selectedBuilds) { it.blockName }) {
        null -> listOf(registerKonanClang(this, konan, library))
        is PrebuiltSettings -> listOf(registerPrebuilt(this, library))
        is ExternalBuildSettings -> ExternalBuild.of(settings).register(this, externalBuildOutput(library))
    }

private fun Project.externalBuildOutput(library: NativeLibrarySpec): ExternalBuildOutput {
    val name = library.name
    return ExternalBuildOutput(
        owner = "konan.libraries.$name",
        name = name,
        side = "",
        targets = library.targets.getOrElse(emptyList()),
        libraryName = provider { name },
        libraryFileName = { provider { NativeArtifacts.archiveFileName(name) } },
        outputDirectory = library::archiveDirectory,
        workDirectory = layout.buildDirectory.dir("konan/libraries/$name"),
        description = { target, tool -> "Build the '$name' library for ${target.konanName} with $tool." },
        extraVariables = { mapOf(ExternalVariable.SOURCE_DIR to library.sourceDirectory.map { it.asFile.absolutePath }) },
    )
}
