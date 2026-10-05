package io.github.lemcoder.library

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.NativeLibrarySpec
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.external.ExternalBuildVariable
import io.github.lemcoder.external.addSteps
import io.github.lemcoder.external.registerExternalBuild
import io.github.lemcoder.taskSuffix
import io.github.lemcoder.wiring.capitalized
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * How a library's archives come to exist: compiled by the plugin, or built by the project's own build
 * system. Every provider must leave one archive per target at [NativeArtifacts.archivePath] under the
 * library's output directory — the only thing consumers know about it.
 */
internal sealed interface LibraryProvider {
    /** Registers the work that builds [library]'s archives. Called once the build script has run. */
    fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec): List<TaskProvider<*>>

    /** The default: the library's sources, compiled with Kotlin/Native's clang. */
    data object KonanClang : LibraryProvider {
        override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec) =
            listOf(registerKonanClang(project, konan, library))
    }

    /** `prebuilt { }`: archives built elsewhere, one per target, copied into place. */
    data object Prebuilt : LibraryProvider {
        override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec) =
            listOf(registerPrebuilt(project, library))
    }

    /**
     * The project's own build makes the archive: one [ExternalBuildTask] per target, run with the
     * [LibraryContract] as its environment, its archive copied to `lib<name>.a`. A build system only
     * adds the commands that build one target.
     */
    sealed class External : LibraryProvider {
        /** Task-name prefix, e.g. `zigBuild` in `zigBuildMymathMacos_arm64`. */
        protected abstract val taskPrefix: String

        /** The build system, for task descriptions. */
        protected abstract val toolName: String

        /** Adds the commands, sources and produced archive that build [contract]'s target. */
        protected abstract fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, contract: LibraryContract)

        final override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec): List<TaskProvider<*>> {
            val libraryName = library.name
            return library.targets.getOrElse(emptyList()).map { target ->
                val contract = LibraryContract(target, libraryName, library.archiveDirectory(target), library.sourceDirectory, project.providers)
                project.registerExternalBuild(
                    taskName = "$taskPrefix${libraryName.capitalized()}${target.taskSuffix}",
                    taskDescription = "Build the '$libraryName' library for ${target.name} with $toolName.",
                    environment = contract.environment(),
                    outputDirectory = contract.outputDirectory,
                    libraryFileName = project.providers.provider { NativeArtifacts.archiveFileName(libraryName) },
                ) {
                    addCommands(this, project, library, contract)
                }
            }
        }

        /** `external { }`: any build system, as the commands the build script lists. */
        data object Generic : External() {
            override val taskPrefix = "externalBuild"
            override val toolName = "its external build"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, contract: LibraryContract) =
                task.addSteps(library.external, contract.target, "konan.libraries.${library.name}")
        }

        /** `zig { }`: the project's own `build.zig`, once per target. */
        data object Zig : External() {
            override val taskPrefix = "zigBuild"
            override val toolName = "zig build"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, contract: LibraryContract) =
                addZigCommands(task, project, library, contract)
        }
    }
}

/** What the plugin tells an external library build about one target. */
internal class LibraryContract(
    val target: KonanTarget,
    private val libraryName: String,
    val outputDirectory: Provider<Directory>,
    private val sourceDirectory: Provider<Directory>,
    private val providers: ProviderFactory,
) {
    fun environment(): Map<ExternalLibraryVariable, Provider<String>> = mapOf(
        ExternalLibraryVariable.LIB_NAME to providers.provider { libraryName },
        ExternalLibraryVariable.OUTPUT_DIR to outputDirectory.map { it.asFile.absolutePath },
        ExternalLibraryVariable.SOURCE_DIR to sourceDirectory.map { it.asFile.absolutePath },
        ExternalLibraryVariable.TARGET to providers.provider { target.name },
    )
}

/** The environment of every external library build — the names are public contract. */
internal enum class ExternalLibraryVariable(override val variableName: String) : ExternalBuildVariable {
    /** The library's name: its archive must become `lib<name>.a`. */
    LIB_NAME("KONAN_LIBRARY_NAME"),

    /** Where this target's archive must land, unless the build names a produced library. */
    OUTPUT_DIR("KONAN_LIBRARY_OUTPUT_DIR"),

    /** The library's `sourceDirectory`. */
    SOURCE_DIR("KONAN_LIBRARY_SOURCE_DIR"),

    /** The Kotlin/Native target, e.g. `linux_x64`. */
    TARGET("KONAN_TARGET"),
}

/** The provider [library] selected: at most one block, else [LibraryProvider.KonanClang]. */
internal fun providerFor(library: NativeLibrarySpec): LibraryProvider {
    val selected = library.selectedProviders
    check(selected.size <= 1) {
        "konan.libraries.${library.name} selects ${selected.joinToString(" and ")}; a library is built one way."
    }
    return selected.singleOrNull() ?: LibraryProvider.KonanClang
}
