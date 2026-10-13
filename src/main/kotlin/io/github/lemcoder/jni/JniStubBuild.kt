package io.github.lemcoder.jni

import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.dsl.JniSpec
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.external.addCMakeCommands
import io.github.lemcoder.external.addMakeCommands
import io.github.lemcoder.external.addSteps
import io.github.lemcoder.external.registerExternalBuilds
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ExternalStubVariable
import io.github.lemcoder.model.ExternalToolchain
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.selectOne
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * How a binding's JNI library — the generated stub, linked with the C library — comes to exist: linked
 * by the plugin, or built by the project's own build system. Every build leaves the library in
 * `jni.libraryDirectoryFor(target)`, so consumers never learn which one ran.
 */
internal sealed interface JniStubBuild {
    /**
     * Registers the work that builds the JNI library and sets `jni.libraryDirectory`'s convention.
     * Called once the build script has run; returns the tasks `linkJni<Name>` depends on.
     */
    fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: JniStubInputs): List<TaskProvider<*>>

    /** The default: the stub and the library's archive, linked per target with Kotlin/Native's toolchain. */
    data object KonanLink : JniStubBuild {
        override fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: JniStubInputs) =
            registerKonanLink(project, konan, binding, inputs)
    }

    /**
     * The project's own build compiles the stub, once per target, told the [ExternalStubVariable]
     * contract. A build system says which targets it builds and adds its commands.
     */
    sealed class External : JniStubBuild {
        protected abstract val taskPrefix: String

        /** The build system, for task descriptions. */
        protected abstract val toolName: String

        protected abstract fun targets(binding: BindingSpec, inputs: JniStubInputs): List<NativeTarget>

        protected abstract fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext)

        /** Whose compilers the build gets. */
        protected abstract fun toolchain(binding: BindingSpec): ExternalToolchain

        /** `jni.libraryDirectory`'s convention, given the plugin's [default]. */
        protected open fun libraryDirectory(binding: BindingSpec, default: Provider<Directory>): Provider<Directory> = default

        final override fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: JniStubInputs): List<TaskProvider<*>> {
            val bindingName = binding.name
            val jni = binding.jni
            val targets = targets(binding, inputs)
            val tasks = project.registerExternalBuilds(
                taskPrefix = taskPrefix,
                owner = bindingName,
                description = { target -> "Build the '$bindingName' JNI library for ${target.konanName} with $toolName." },
                targets = targets,
                contextFor = { target ->
                    val output = jni.libraryDirectoryFor(target)
                    ExternalBuildContext(
                        target, output,
                        mapOf(
                            ExternalStubVariable.STUB_DIR to jni.stubDirectory.map { it.asFile.absolutePath },
                            ExternalStubVariable.LIB_NAME to jni.stubLibraryName,
                            ExternalStubVariable.OUTPUT_DIR to output.map { it.asFile.absolutePath },
                            ExternalStubVariable.TARGET to project.provider { target.konanName },
                            ExternalStubVariable.ABI to project.provider { target.libraryDirectoryName },
                            ExternalStubVariable.JNI_INCLUDE_DIR to inputs.generate.flatMap { it.jniIncludeDirectory }.map { it.asFile.absolutePath },
                        ),
                    )
                },
                libraryFileName = { target -> jni.stubLibraryName.map { target.sharedLibraryName(it) } },
                toolchain = toolchain(binding),
            ) { context ->
                dependsOn(inputs.generate)
                generatedSources.from(jni.stubDirectory)
                addCommands(this, project, binding, context)
            }
            val host = NativeTarget.host
            jni.libraryDirectory.convention(libraryDirectory(binding, if (host in targets) jni.libraryDirectoryFor(host) else jni.jniLibsRoot))
            return tasks
        }

        /** `jni { external { } }`: any build system, as the commands the build script lists. */
        data object Generic : External() {
            override val taskPrefix = "externalBuildJni"
            override val toolName = "its external build"
            override fun targets(binding: BindingSpec, inputs: JniStubInputs) = inputs.targets
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) =
                task.addSteps(binding.jni.external, context.target, "konan.bindings.${binding.name}.jni")
            override fun toolchain(binding: BindingSpec) = binding.jni.external.toolchain.get()
        }

        /** `jni { make { } }`: the project's Makefile, told where the stub is and what to call the library. */
        data object Make : External() {
            override val taskPrefix = "makeBuildJni"
            override val toolName = "make"
            override fun targets(binding: BindingSpec, inputs: JniStubInputs) = inputs.targets
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) =
                task.addMakeCommands(binding.jni.make, context, "konan.bindings.${binding.name}.jni.make")
            override fun toolchain(binding: BindingSpec) = binding.jni.make.toolchain.get()
        }

        /** `jni { cmake { } }`: a CMakeLists, configured with the contract as cache entries, then built. */
        data object CMake : External() {
            override val taskPrefix = "cmakeBuildJni"
            override val toolName = "CMake"
            override fun targets(binding: BindingSpec, inputs: JniStubInputs) =
                binding.jni.cmake.targetSettings.keys.toList().ifEmpty { listOf(NativeTarget.host) }
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, context: ExternalBuildContext) =
                task.addCMakeCommands(
                    project, binding.jni.cmake, context, "konan.bindings.${binding.name}.jni.cmake",
                    project.layout.buildDirectory.dir("konan/bindings/${binding.name}/jni/cmake"),
                )
            override fun libraryDirectory(binding: BindingSpec, default: Provider<Directory>) =
                binding.jni.cmake.libraryDirectory.orElse(default)
            override fun toolchain(binding: BindingSpec) = binding.jni.cmake.toolchain.get()
        }
    }
}

/** The build [jni] selected: at most one block, else [JniStubBuild.KonanLink]. */
internal fun stubBuildFor(bindingName: String, jni: JniSpec): JniStubBuild =
    selectOne("konan.bindings.$bindingName.jni", jni.selectedStubBuilds, JniStubBuild.KonanLink)

/** What every stub build gets from the JNI consumer. */
internal class JniStubInputs(
    /** The generator, which the build must run after. */
    val generate: TaskProvider<GenerateJniBindingsTask>,
    /** The archives of the binding's library, if it names one. */
    val artifacts: NativeArtifacts?,
    /** The JNI targets: `jni.targets`, else the library's that a JVM can load, else the host. */
    val targets: List<NativeTarget>,
)
