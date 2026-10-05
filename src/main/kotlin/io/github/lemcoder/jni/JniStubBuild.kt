package io.github.lemcoder.jni

import io.github.lemcoder.abiDir
import io.github.lemcoder.cleanTask
import io.github.lemcoder.dsl.BindingSpec
import io.github.lemcoder.dsl.JniSpec
import io.github.lemcoder.dsl.KonanExtension
import io.github.lemcoder.external.ExternalBuildTask
import io.github.lemcoder.hostKonanTarget
import io.github.lemcoder.library.NativeArtifacts
import io.github.lemcoder.sharedLibraryName
import io.github.lemcoder.taskSuffix
import io.github.lemcoder.toolchain.NativeToolchain
import io.github.lemcoder.wiring.capitalized
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.TaskProvider
import org.jetbrains.kotlin.konan.target.KonanTarget

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
     * The project's own build compiles the stub: one [ExternalBuildTask] per target, run with the
     * [ExternalContract] as its environment. A build system says only which targets it builds and which
     * commands build one.
     */
    sealed class External : JniStubBuild {
        /** Task-name prefix, e.g. `cmakeBuild` in `cmakeBuildMymathMacos_arm64`. */
        protected abstract val taskPrefix: String

        /** The build system, for task descriptions. */
        protected abstract val toolName: String

        /** The targets this build runs for. */
        protected abstract fun targets(binding: BindingSpec, inputs: JniStubInputs): List<KonanTarget>

        /** Adds the commands, and any sources or produced library, that build [contract]'s target. */
        protected abstract fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, contract: ExternalContract)

        /** `jni.libraryDirectory`'s convention, given the plugin's [default]. */
        protected open fun libraryDirectory(binding: BindingSpec, default: Provider<Directory>): Provider<Directory> = default

        final override fun register(project: Project, konan: KonanExtension, binding: BindingSpec, inputs: JniStubInputs): List<TaskProvider<*>> {
            val bindingName = binding.name
            val jni = binding.jni
            val javaHome = jni.jdkHome.map { it.asFile.absolutePath }
                .orElse(project.provider { NativeToolchain.detectJniHome().absolutePath })
            val targets = targets(binding, inputs)
            val tasks = targets.map { target ->
                val contract = ExternalContract(target, jni.stubDirectory, jni.stubLibraryName, jni.libraryDirectoryFor(target), javaHome, project.providers)
                project.tasks.register("$taskPrefix${bindingName.capitalized()}${target.taskSuffix}", ExternalBuildTask::class.java) {
                    group = "konan"
                    description = "Build the '$bindingName' JNI library for ${target.name} with $toolName."
                    dependsOn(inputs.generate)
                    mustRunAfter(project.cleanTask())
                    contract.environment().forEach { (variable, value) -> environment.put(variable.variableName, value) }
                    generatedSources.from(contract.stubDirectory)
                    libraryFileName.set(contract.libraryName.map { target.sharedLibraryName(it) })
                    outputDirectory.set(contract.outputDirectory)
                    addCommands(this, project, binding, contract)
                }
            }
            val host = hostKonanTarget()
            jni.libraryDirectory.convention(libraryDirectory(binding, if (host in targets) jni.libraryDirectoryFor(host) else jni.jniLibsRoot))
            return tasks
        }

        /** `jni { external { } }`: any build system, as the commands the build script lists. */
        data object Generic : External() {
            override val taskPrefix = "externalBuild"
            override val toolName = "its external build"
            override fun targets(binding: BindingSpec, inputs: JniStubInputs) = inputs.targets
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, contract: ExternalContract) =
                addGenericCommands(task, binding, contract)
        }

        /** `jni { cmake { } }`: a CMakeLists, configured with the contract as cache entries, then built. */
        data object CMake : External() {
            override val taskPrefix = "cmakeBuild"
            override val toolName = "CMake"
            override fun targets(binding: BindingSpec, inputs: JniStubInputs) =
                binding.jni.cmake.targetSettings.keys.toList().ifEmpty { listOf(hostKonanTarget()) }
            override fun addCommands(task: ExternalBuildTask, project: Project, binding: BindingSpec, contract: ExternalContract) =
                addCMakeCommands(task, project, binding, contract)
            override fun libraryDirectory(binding: BindingSpec, default: Provider<Directory>) =
                binding.jni.cmake.libraryDirectory.orElse(default)
        }
    }
}

/** What the plugin tells an external build about one target. */
internal class ExternalContract(
    val target: KonanTarget,
    val stubDirectory: Provider<Directory>,
    val libraryName: Provider<String>,
    val outputDirectory: Provider<Directory>,
    val javaHome: Provider<String>,
    private val providers: ProviderFactory,
) {
    fun environment(): Map<ExternalStubVariable, Provider<String>> = mapOf(
        ExternalStubVariable.STUB_DIR to stubDirectory.map { it.asFile.absolutePath },
        ExternalStubVariable.LIB_NAME to libraryName,
        ExternalStubVariable.OUTPUT_DIR to outputDirectory.map { it.asFile.absolutePath },
        ExternalStubVariable.TARGET to providers.provider { target.name },
        ExternalStubVariable.ABI to providers.provider { target.abiDir },
        ExternalStubVariable.JAVA_HOME to javaHome,
    )
}

/**
 * The environment of every external build — the names are the public contract, read the way an AGP
 * project reads `ANDROID_ABI`.
 */
internal enum class ExternalStubVariable(val variableName: String) {
    /** The directory holding the generated `.c` stub. */
    STUB_DIR("KONAN_JNI_STUB_DIR"),

    /** The library name the generated bindings pass to `System.loadLibrary`. */
    LIB_NAME("KONAN_JNI_LIB_NAME"),

    /** Where the target's library must land, unless the build names a produced library. */
    OUTPUT_DIR("KONAN_JNI_OUTPUT_DIR"),

    /** The Kotlin/Native target, e.g. `android_arm64`. */
    TARGET("KONAN_TARGET"),

    /** The target's Android ABI, e.g. `arm64-v8a`; the target's name elsewhere. */
    ABI("KONAN_JNI_ABI"),

    /** A JDK that ships `include/jni.h`. */
    JAVA_HOME("JAVA_HOME"),
}

/** The build [jni] selected: at most one block, else [JniStubBuild.KonanLink]. */
internal fun stubBuildFor(bindingName: String, jni: JniSpec): JniStubBuild {
    val selected = jni.selectedStubBuilds
    check(selected.size <= 1) {
        "konan.bindings.$bindingName.jni selects ${selected.joinToString(" and ")}; a JNI library is built one way."
    }
    return selected.singleOrNull() ?: JniStubBuild.KonanLink
}

/** What every stub build gets from the JNI consumer. */
internal class JniStubInputs(
    /** The generator, which the build must run after. */
    val generate: TaskProvider<GenerateJniBindingsTask>,
    /** The archives of the binding's library, if it names one. */
    val artifacts: NativeArtifacts?,
    /** The JNI targets: `jni.targets`, else the library's that a JVM can load, else the host. */
    val targets: List<KonanTarget>,
)
