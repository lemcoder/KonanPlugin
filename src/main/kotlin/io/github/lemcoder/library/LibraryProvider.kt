package io.github.lemcoder.library

import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.dsl.NativeLibrarySpec
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.external.addCMakeCommands
import io.github.lemcoder.external.addMakeCommands
import io.github.lemcoder.external.addSteps
import io.github.lemcoder.external.registerExternalBuilds
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ExternalLibraryVariable
import io.github.lemcoder.model.ExternalToolchain
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.selectOne
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * How a library's archives come to exist: compiled by the plugin, built by the project's own build
 * system, or brought in prebuilt. Every provider leaves one archive per target at
 * [NativeArtifacts.archivePath] under the library's output directory — the only thing consumers know.
 */
internal sealed interface LibraryProvider {
    /** Registers the work that builds [library]'s archives. Called once the build script has run. */
    fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec): List<TaskProvider<*>>

    /** The default: the library's sources, compiled with Kotlin/Native's clang. */
    data object KonanClang : LibraryProvider {
        override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec) =
            listOf(registerKonanClang(project, konan, library))
    }

    /** `prebuilt { }`: archives built elsewhere, one per target, placed in the layout. */
    data object Prebuilt : LibraryProvider {
        override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec) =
            listOf(registerPrebuilt(project, library))
    }

    /**
     * The project's own build makes the archive, once per target, told the [ExternalLibraryVariable]
     * contract; its archive is copied to `lib<name>.a`. A build system only adds its commands.
     */
    sealed class External : LibraryProvider {
        protected abstract val taskPrefix: String

        /** The build system, for task descriptions. */
        protected abstract val toolName: String

        protected abstract fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext)

        /** Whose compilers the build gets; null when the build system brings its own. */
        protected abstract fun toolchain(library: NativeLibrarySpec): ExternalToolchain?

        /** Whether those compilers go into the environment as `CC`, `CXX`, `AR`. */
        protected open val toolchainInEnvironment: Boolean = true

        final override fun register(project: Project, konan: KonanExtension, library: NativeLibrarySpec): List<TaskProvider<*>> {
            val libraryName = library.name
            val fileName = project.providers.provider { NativeArtifacts.archiveFileName(libraryName) }
            return project.registerExternalBuilds(
                taskPrefix = taskPrefix,
                owner = libraryName,
                description = { target -> "Build the '$libraryName' library for ${target.konanName} with $toolName." },
                targets = library.targets.getOrElse(emptyList()),
                contextFor = { target ->
                    val output = library.archiveDirectory(target)
                    ExternalBuildContext(
                        target, output,
                        mapOf(
                            ExternalLibraryVariable.LIB_NAME to project.providers.provider { libraryName },
                            ExternalLibraryVariable.OUTPUT_DIR to output.map { it.asFile.absolutePath },
                            ExternalLibraryVariable.SOURCE_DIR to library.sourceDirectory.map { it.asFile.absolutePath },
                            ExternalLibraryVariable.TARGET to project.providers.provider { target.konanName },
                        ),
                    )
                },
                libraryFileName = { fileName },
                toolchain = toolchain(library),
                toolchainInEnvironment = toolchainInEnvironment,
            ) { context -> addCommands(this, project, library, context) }
        }

        /** `external { }`: any build system, as the commands the build script lists. */
        data object Generic : External() {
            override val taskPrefix = "externalBuild"
            override val toolName = "its external build"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext) =
                task.addSteps(library.external, context.target, "konan.libraries.${library.name}")
            override fun toolchain(library: NativeLibrarySpec) = library.external.toolchain.get()
        }

        /** `cmake { }`: the project's CMakeLists, building a static library per target. */
        data object CMake : External() {
            override val taskPrefix = "cmakeBuild"
            override val toolName = "CMake"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext) {
                val cmake = library.cmake
                task.addCMakeCommands(
                    project, cmake, context, "konan.libraries.${library.name}.cmake",
                    project.layout.buildDirectory.dir("konan/libraries/${library.name}/cmake"),
                )
                // A build that ignores CMAKE_ARCHIVE_OUTPUT_DIRECTORY: its archive is copied into place.
                task.producedLibrary.set(cmake.libraryDirectory.zip(task.libraryFileName) { directory, file -> directory.file(file) })
            }
            override fun toolchain(library: NativeLibrarySpec) = library.cmake.toolchain.get()
        }

        /** `make { }`: the project's Makefile, with Kotlin/Native's compilers when cross-compiling. */
        data object Make : External() {
            override val taskPrefix = "makeBuild"
            override val toolName = "make"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext) =
                task.addMakeCommands(library.make, context, "konan.libraries.${library.name}.make")
            override fun toolchain(library: NativeLibrarySpec) = library.make.toolchain.get()
        }

        /** `cargo { }`: a Rust crate built as a static library, per Rust target triple. */
        data object Cargo : External() {
            override val taskPrefix = "cargoBuild"
            override val toolName = "cargo"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext) =
                addCargoCommands(task, project, library, context)
            override fun toolchain(library: NativeLibrarySpec): ExternalToolchain = library.cargo.toolchain.get()
            // Under the cc crate's target-specific names instead; see addCargoCommands.
            override val toolchainInEnvironment = false
        }

        /** `zig { }`: the project's own `build.zig`. */
        data object Zig : External() {
            override val taskPrefix = "zigBuild"
            override val toolName = "zig build"
            override fun addCommands(task: ExternalBuildTask, project: Project, library: NativeLibrarySpec, context: ExternalBuildContext) =
                addZigCommands(task, project, library, context)
            // Zig is its own cross toolchain.
            override fun toolchain(library: NativeLibrarySpec): ExternalToolchain? = null
        }
    }
}

/** The provider [library] selected: at most one block, else [LibraryProvider.KonanClang]. */
internal fun providerFor(library: NativeLibrarySpec): LibraryProvider =
    selectOne("konan.libraries.${library.name}", library.selectedProviders, LibraryProvider.KonanClang)
