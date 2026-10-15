package io.github.lemcoder.external

import io.github.lemcoder.dsl.builds.CMakeSettings
import io.github.lemcoder.dsl.builds.CargoSettings
import io.github.lemcoder.dsl.builds.ExternalBuildSettings
import io.github.lemcoder.dsl.builds.ExternalToolchain
import io.github.lemcoder.dsl.builds.MakeSettings
import io.github.lemcoder.dsl.builds.StepsSettings
import io.github.lemcoder.dsl.builds.ZigSettings
import io.github.lemcoder.kotlin.KonanToolchain
import io.github.lemcoder.model.ExternalBuildContext
import io.github.lemcoder.model.ExternalBuildVariable
import io.github.lemcoder.model.ExternalVariable
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.taskName
import io.github.lemcoder.util.cleanTask
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/**
 * What an external build makes, for each of [targets], and where it must leave it. Owners differ only in
 * this — a build system's commands are the same for a library, a JNI stub and the library FFM loads.
 */
internal class ExternalBuildOutput(
    /** The owning block's DSL path, for messages: `konan.libraries.mymath`, `konan.bindings.mymath.jni`. */
    val owner: String,
    /** The library's or binding's name, in task names. */
    val name: String,
    /** Between the build system and [name] in task names: `""`, `"Jni"`, `"Ffm"` — `cmakeBuildJniMymath…`. */
    val side: String,
    val targets: List<NativeTarget>,
    /** The [ExternalVariable.LIB_NAME] the build is told. */
    val libraryName: Provider<String>,
    /** The file the build must leave in [outputDirectory]: `lib<name>.a`, or a shared library. */
    val libraryFileName: (NativeTarget) -> Provider<String>,
    val outputDirectory: (NativeTarget) -> Provider<Directory>,
    /** Where a build system's own files go: CMake's build trees, Cargo's target directory, Zig's prefixes. */
    val workDirectory: Provider<Directory>,
    /** A task description, given the target and the build system's name. */
    val description: (NativeTarget, String) -> String,
    /** Variables only this kind of output has, e.g. [ExternalVariable.JNI_STUB_DIR]. */
    val extraVariables: (NativeTarget) -> Map<ExternalBuildVariable, Provider<String>> = { emptyMap() },
    /** Further wiring of every task, e.g. the generated stub as an input. */
    val configure: ExternalBuildTask.() -> Unit = {},
)

/**
 * The project's own build system, configured by one [ExternalBuildSettings] block, run once per target as an
 * [ExternalBuildTask] told the [ExternalVariable] contract. A build system only adds its commands; a new one
 * is one more subclass, a settings class and a DSL block.
 *
 * Where [toolchain] picks Kotlin/Native's for a target, the build waits for its files and the context
 * carries them — and, with [toolchainInEnvironment], its [io.github.lemcoder.model.ToolchainVariable]s
 * under the standard names. Null means the build system brings its own (Zig).
 */
internal sealed class ExternalBuild(private val taskPrefix: String, private val toolName: String) {
    protected abstract val toolchain: ExternalToolchain?

    protected open val toolchainInEnvironment: Boolean get() = true

    protected abstract fun ExternalBuildTask.addCommands(project: Project, output: ExternalBuildOutput, context: ExternalBuildContext)

    fun register(project: Project, output: ExternalBuildOutput): List<TaskProvider<ExternalBuildTask>> {
        val clean = project.cleanTask()
        val toolchain = toolchain
        return output.targets.map { target ->
            val konanToolchain = toolchain?.takeIf { it.usesKonan(target) }?.let { KonanToolchain.of(project, target) }
            val targetOutput = output.outputDirectory(target)
            val contract: Map<ExternalBuildVariable, Provider<String>> = mapOf(
                ExternalVariable.LIB_NAME to output.libraryName,
                ExternalVariable.OUTPUT_DIR to targetOutput.map { it.asFile.absolutePath },
                ExternalVariable.TARGET to project.provider { target.konanName },
                ExternalVariable.ABI to project.provider { target.libraryDirectoryName },
            ) + output.extraVariables(target)
            val environment = if (konanToolchain != null && toolchainInEnvironment) contract + konanToolchain.files.environment else contract
            val context = ExternalBuildContext(target, targetOutput, environment, konanToolchain?.files)
            val taskDescription = output.description(target, toolName)
            val fileName = output.libraryFileName(target)
            project.tasks.register(taskName(taskPrefix + output.side, output.name, target), ExternalBuildTask::class.java) {
                group = "konan"
                description = taskDescription
                mustRunAfter(clean)
                konanToolchain?.let { dependsOn(it.task) }
                context.environment.forEach { (variable, value) -> this.environment.put(variable.variableName, value) }
                libraryFileName.set(fileName)
                outputDirectory.set(targetOutput)
                output.configure(this)
                addCommands(project, output, context)
            }
        }
    }

    companion object {
        /** The build system [settings] configure. */
        fun of(settings: ExternalBuildSettings): ExternalBuild = when (settings) {
            is StepsSettings -> Generic(settings)
            is MakeSettings -> Make(settings)
            is CMakeSettings -> CMake(settings)
            is CargoSettings -> Cargo(settings)
            is ZigSettings -> Zig(settings)
        }
    }

    /** `external { }`: any build system, as the commands the build script lists. */
    data class Generic(val settings: StepsSettings) : ExternalBuild("externalBuild", "its external build") {
        override val toolchain get() = settings.toolchain.get()
        override fun ExternalBuildTask.addCommands(project: Project, output: ExternalBuildOutput, context: ExternalBuildContext) =
            addSteps(settings, context.target, output.owner)
    }

    /** `make { }`: the project's Makefile, with Kotlin/Native's compilers when cross-compiling. */
    data class Make(val settings: MakeSettings) : ExternalBuild("makeBuild", "make") {
        override val toolchain get() = settings.toolchain.get()
        override fun ExternalBuildTask.addCommands(project: Project, output: ExternalBuildOutput, context: ExternalBuildContext) =
            addMakeCommands(settings, context, "${output.owner}.make")
    }

    /** `cmake { }`: the project's CMakeLists, configured with the contract as cache entries, then built. */
    data class CMake(val settings: CMakeSettings) : ExternalBuild("cmakeBuild", "CMake") {
        override val toolchain get() = settings.toolchain.get()
        override fun ExternalBuildTask.addCommands(project: Project, output: ExternalBuildOutput, context: ExternalBuildContext) {
            addCMakeCommands(project, settings, context, "${output.owner}.cmake", output.workDirectory.map { it.dir("cmake") })
            // A build that ignores CMAKE_*_OUTPUT_DIRECTORY: its library is copied into place.
            producedLibrary.set(settings.libraryDirectory.zip(libraryFileName) { directory, file -> directory.file(file) })
        }
    }

    /** `cargo { }`: a Rust crate built as a static library, per Rust target triple. */
    data class Cargo(val settings: CargoSettings) : ExternalBuild("cargoBuild", "cargo") {
        override val toolchain get() = settings.toolchain.get()
        // Under the cc crate's target-specific names instead; see addCargoCommands.
        override val toolchainInEnvironment get() = false
        override fun ExternalBuildTask.addCommands(project: Project, output: ExternalBuildOutput, context: ExternalBuildContext) =
            addCargoCommands(settings, output.workDirectory.map { it.dir("cargo") }, context)
    }

    /** `zig { }`: the project's own `build.zig`. */
    data class Zig(val settings: ZigSettings) : ExternalBuild("zigBuild", "zig build") {
        // Zig is its own cross toolchain.
        override val toolchain: ExternalToolchain? get() = null
        override fun ExternalBuildTask.addCommands(project: Project, output: ExternalBuildOutput, context: ExternalBuildContext) =
            addZigCommands(settings, output.workDirectory.map { it.dir("zig") }, context)
    }
}

/** The build script's own steps, as they are, for [target]: what a generic `external { }` block runs. */
internal fun ExternalBuildTask.addSteps(settings: StepsSettings, target: NativeTarget, owner: String) {
    check(settings.steps.isNotEmpty()) { "$owner.external declares no step." }
    settings.steps.forEach { step ->
        command {
            executable.set(step.executable)
            arguments.addAll(step.arguments)
            arguments.addAll(step.targetArguments.getting(target).orElse(emptyList()))
            environment.putAll(step.environment)
            workingDirectory.set(step.workingDirectory.map { it.asFile.absolutePath })
        }
    }
    sources.from(settings.sources)
    producedLibrary.fileProvider(settings.producedLibraries.getting(target))
}
