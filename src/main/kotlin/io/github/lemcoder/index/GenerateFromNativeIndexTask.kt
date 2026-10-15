package io.github.lemcoder.index

import io.github.lemcoder.kotlin.UsesKonanDistribution
import io.github.lemcoder.kotlin.distribution
import io.github.lemcoder.kotlin.runIndexExporter
import io.github.lemcoder.model.ConfigValue
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/**
 * Indexes the headers a `.def` names with cinterop's own indexer and runs the generator [request] asks for over the
 * resulting `NativeIndex`, in the exporter's process, writing into [outputDirectory]. See
 * [runIndexExporter] for where that process runs.
 */
@DisableCachingByDefault(because = "Output depends on the local Kotlin/Native distribution, which is not a tracked input")
abstract class GenerateFromNativeIndexTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask(), UsesKonanDistribution {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defFile: RegularFileProperty

    /** Each directory becomes an `-I` for the indexer's clang, after the `.def`'s own directory. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    @get:Input @get:Optional
    abstract val compilerOptions: ListProperty<String>

    /** Further cinterop options, as cinterop spells them, e.g. `-libraryPath <dir>`. */
    @get:Input @get:Optional
    abstract val extraCinteropArgs: ListProperty<String>

    @get:Input
    abstract val target: Property<NativeTarget>

    /** One of [io.github.lemcoder.kotlin.KonanDistribution.supportedVersions]: the exporter links against its compiler. */
    @get:Internal abstract override val konanHome: Property<ConfigValue>

    /** The jextract program, when [request] is [GeneratorRequest.Jextract]; checked when the task runs. */
    @get:Internal abstract val jextract: Property<ConfigValue>

    /** What to generate, with that generator's settings, e.g. `GeneratorRequest.Dump`. */
    @get:Input
    abstract val request: Property<GeneratorRequest>

    /** Replaced on every run with the generator's output. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val request = request.get()
        exec.runIndexExporter(
            request = if (request is GeneratorRequest.Jextract && jextract.isPresent) request.copy(jextract = jextract.get().require()) else request,
            outputDirectory = outputDirectory.get().asFile,
            cinteropArgs = buildList {
                val def = defFile.get().asFile
                add("-def"); add(def.absolutePath)
                // The .def's own directory, as cinterop users expect; a path rather than an input, so a .def
                // at the project root does not make the whole project one.
                add("-compiler-option"); add("-I${def.parentFile.absolutePath}")
                add("-target"); add(target.get().konanName)
                includeDirs.files.forEach { add("-compiler-option"); add("-I${it.absolutePath}") }
                compilerOptions.getOrElse(emptyList()).forEach { add("-compiler-option"); add(it) }
                addAll(extraCinteropArgs.getOrElse(emptyList()))
            },
            distribution = distribution(),
            workDir = temporaryDir,
        )
        if (request is GeneratorRequest.Jni) writeJniHeader(outputDirectory.get().asFile.resolve(GeneratorRequest.Jni.INCLUDE_DIR))
    }

    /**
     * Android's `jni.h` — VM-neutral and Apache-licensed — beside the stub, for compiling it for a desktop
     * JVM without a JDK. Android builds use the NDK sysroot's.
     */
    private fun writeJniHeader(directory: File) {
        val header = directory.resolve("jni.h").apply { parentFile.mkdirs() }
        GenerateFromNativeIndexTask::class.java.getResourceAsStream("/io/github/lemcoder/bindings/jni/jni.h").use { input ->
            header.outputStream().use { checkNotNull(input) { "jni.h missing from the plugin jar" }.copyTo(it) }
        }
    }
}
