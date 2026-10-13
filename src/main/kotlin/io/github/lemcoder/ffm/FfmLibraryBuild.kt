package io.github.lemcoder.ffm

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.external.addCMakeCommands
import io.github.lemcoder.external.addMakeCommands
import io.github.lemcoder.external.addSteps
import io.github.lemcoder.external.registerExternalBuilds
import io.github.lemcoder.library.PlacePrebuiltFilesTask
import io.github.lemcoder.link.LinkSharedLibraryTask
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ExternalFfmVariable
import io.github.lemcoder.model.ExternalToolchain
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.selectOne
import io.github.lemcoder.model.taskName
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * How the shared library FFM bindings load comes to exist, per target: linked by the plugin from the
 * binding's library, built by the project's own build, or built elsewhere. FFM needs no stub — this is the
 * C library itself. Every build leaves `ffm.libraryDirectoryFor(target)/<lib><libraryName><suffix>`.
 */
internal sealed interface FfmLibraryBuild {
    /** Registers the work for [inputs]' targets. Called once the build script has run. */
    fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: FfmLibraryInputs): List<TaskProvider<*>>

    /** The default: the binding's library's archive, linked whole into a shared library with Kotlin/Native's clang. */
    data object KonanLink : FfmLibraryBuild {
        override fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: FfmLibraryInputs): List<TaskProvider<*>> {
            val artifacts = inputs.artifacts
            if (artifacts == null) {
                // Nothing to link: a platform library (libaaudio), or one the app gets some other way.
                project.logger.info(
                    "konan.bindings.${binding.name}.ffm: no library(), so the bindings load lib${inputs.libraryName} as the system provides it."
                )
                return emptyList()
            }
            val built = artifacts.targets.getOrElse(emptyList())
            val (linked, missing) = inputs.targets.partition { it in built }
            if (missing.isNotEmpty()) {
                project.logger.warn(
                    "konan.bindings.${binding.name}.ffm: no library is built for ${missing.joinToString { it.konanName }}, so nothing " +
                        "is linked there — name one with library(…), or use ffm { external { } } or ffm { prebuilt { } }."
                )
            }
            return linked.map { target -> registerLink(project, konan, binding, artifacts, inputs.libraryName, target) }
        }

        private fun registerLink(
            project: Project,
            konan: KonanExtension,
            binding: BindingSpec,
            artifacts: NativeArtifacts,
            libraryName: String,
            target: NativeTarget,
        ) = project.tasks.register(taskName("linkFfm", binding.name, target), LinkSharedLibraryTask::class.java) {
            group = "konan"
            description = "Link the '$libraryName' library for ${target.konanName}, for the FFM bindings."
            dependsOn(artifacts.builtBy)
            androidApiLevel.set(konan.androidApiLevel)
            this.target.set(target)
            libraryBaseName.set(libraryName)
            staticLibrary.set(artifacts.archive(target))
            additionalLinkerArgs.set(binding.ffm.linkerOptions)
            outputDirectory.set(binding.ffm.libraryDirectoryFor(target))
        }
    }

    /** `ffm { prebuilt { } }`: shared libraries built elsewhere, placed under the name the bindings load. */
    data object Prebuilt : FfmLibraryBuild {
        override fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: FfmLibraryInputs): List<TaskProvider<*>> {
            val libraries = binding.ffm.prebuilt.sharedLibraries.getOrElse(emptyMap())
            val missing = inputs.targets.filter { it !in libraries }
            check(missing.isEmpty()) {
                "konan.bindings.${binding.name}.ffm.prebuilt has no shared library for ${missing.joinToString { it.konanName }}."
            }
            val placements = inputs.targets.associate { target ->
                libraries.getValue(target) to binding.ffm.libraryDirectoryFor(target).get().file(target.sharedLibraryName(inputs.libraryName)).asFile
            }
            return listOf(
                project.tasks.register(taskName("prebuiltFfm", binding.name), PlacePrebuiltFilesTask::class.java) {
                    group = "konan"
                    description = "Place the prebuilt '${inputs.libraryName}' libraries the '${binding.name}' FFM bindings load."
                    place(placements)
                }
            )
        }
    }

    /**
     * The project's own build makes the shared library, once per target, told the [ExternalFfmVariable]
     * contract. A build system says which targets it builds and adds its commands.
     */
    sealed class External : FfmLibraryBuild {
        protected abstract val taskPrefix: String

        /** The build system, for task descriptions. */
        protected abstract val toolName: String

        protected open fun targets(binding: BindingSpec, inputs: FfmLibraryInputs): List<NativeTarget> = inputs.targets

        protected abstract fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext)

        /** Whose compilers the build gets. */
        protected abstract fun toolchain(binding: BindingSpec): ExternalToolchain

        final override fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: FfmLibraryInputs): List<TaskProvider<*>> {
            val ffm = binding.ffm
            val libraryName = inputs.libraryName
            return project.registerExternalBuilds(
                taskPrefix = taskPrefix,
                owner = binding.name,
                description = { target -> "Build the '$libraryName' library for ${target.konanName} with $toolName, for the FFM bindings." },
                targets = targets(binding, inputs),
                contextFor = { target ->
                    val output = ffm.libraryDirectoryFor(target)
                    ExternalBuildContext(
                        target, output,
                        mapOf(
                            ExternalFfmVariable.LIB_NAME to project.provider { libraryName },
                            ExternalFfmVariable.OUTPUT_DIR to output.map { it.asFile.absolutePath },
                            ExternalFfmVariable.TARGET to project.provider { target.konanName },
                            ExternalFfmVariable.ABI to project.provider { target.libraryDirectoryName },
                        ),
                    )
                },
                libraryFileName = { target -> project.provider { target.sharedLibraryName(libraryName) } },
                toolchain = toolchain(binding),
            ) { context -> addCommands(this, project, binding, context) }
        }

        /** `ffm { external { } }`: any build system, as the commands the build script lists. */
        data object Generic : External() {
            override val taskPrefix = "externalBuildFfm"
            override val toolName = "its external build"
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) =
                task.addSteps(binding.ffm.external, context.target, "konan.bindings.${binding.name}.ffm")
            override fun toolchain(binding: BindingSpec) = binding.ffm.external.toolchain.get()
        }

        /** `ffm { make { } }`: the project's Makefile, told what to call the library and where to leave it. */
        data object Make : External() {
            override val taskPrefix = "makeBuildFfm"
            override val toolName = "make"
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) =
                task.addMakeCommands(binding.ffm.make, context, "konan.bindings.${binding.name}.ffm.make")
            override fun toolchain(binding: BindingSpec) = binding.ffm.make.toolchain.get()
        }

        /**
         * `ffm { cmake { } }`: a CMakeLists, configured with the contract as cache entries, then built — for
         * its `target(…)` blocks if it has any, else for the FFM targets.
         */
        data object CMake : External() {
            override val taskPrefix = "cmakeBuildFfm"
            override val toolName = "CMake"
            override fun targets(binding: BindingSpec, inputs: FfmLibraryInputs) =
                binding.ffm.cmake.targetSettings.keys.toList().ifEmpty { inputs.targets }
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) {
                val cmake = binding.ffm.cmake
                task.addCMakeCommands(
                    project, cmake, context, "konan.bindings.${binding.name}.ffm.cmake",
                    project.layout.buildDirectory.dir("konan/bindings/${binding.name}/ffm/cmake"),
                )
                // A build that ignores CMAKE_LIBRARY_OUTPUT_DIRECTORY: its library is copied into place.
                task.producedLibrary.set(cmake.libraryDirectory.zip(task.libraryFileName) { directory, file -> directory.file(file) })
            }
            override fun toolchain(binding: BindingSpec) = binding.ffm.cmake.toolchain.get()
        }
    }
}

/** The build [binding]'s `ffm` selected: at most one block, else [FfmLibraryBuild.KonanLink]. */
internal fun ffmLibraryBuildFor(binding: BindingSpec): FfmLibraryBuild =
    selectOne("konan.bindings.${binding.name}.ffm", binding.ffm.selectedLibraryBuilds, FfmLibraryBuild.KonanLink)

/** What every FFM library build gets from the FFM consumer. */
internal class FfmLibraryInputs(
    /** The archives of the binding's library, if it names one. */
    val artifacts: NativeArtifacts?,
    /** `ffm.targets`, resolved for the project: the host on a JVM, 64-bit ABIs on Android. */
    val targets: List<NativeTarget>,
    /** The name the bindings load. */
    val libraryName: String,
)
