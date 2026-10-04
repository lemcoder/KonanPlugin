package io.github.lemcoder.index

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
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
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

/**
 * Indexes the headers a `.def` names with cinterop's own indexer and runs a [generator] over the
 * resulting `NativeIndex`, in the exporter's process, writing into [outputDirectory]. See
 * [runIndexExporter] for where that process runs.
 */
@DisableCachingByDefault(because = "Output depends on the local Kotlin/Native distribution, which is not a tracked input")
abstract class GenerateFromNativeIndexTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defFile: RegularFileProperty

    /** Each directory becomes an `-I` for the indexer's clang. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    @get:Input @get:Optional
    abstract val compilerOptions: ListProperty<String>

    @get:Input
    abstract val target: Property<KonanTarget>

    /** Defaults to the installed distribution of [PinnedKotlinNative.version], found at execution. */
    @get:Internal
    abstract val konanHome: DirectoryProperty

    /** A generator the exporter knows by name, e.g. `dump` or `jni`. */
    @get:Input
    abstract val generator: Property<String>

    /** Passed to the generator as `key=value` options. */
    @get:Input @get:Optional
    abstract val generatorOptions: MapProperty<String, String>

    /** Replaced on every run with the generator's output. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        exec.runIndexExporter(
            generator = generator.get(),
            outputDirectory = outputDirectory.get().asFile,
            cinteropArgs = buildList {
                add("-def"); add(defFile.get().asFile.absolutePath)
                add("-target"); add(target.get().name)
                includeDirs.files.forEach { add("-compiler-option"); add("-I${it.absolutePath}") }
                compilerOptions.getOrElse(emptyList()).forEach { add("-compiler-option"); add(it) }
            },
            generatorOptions = generatorOptions.getOrElse(emptyMap()),
            konanHome = konanHome.orNull?.asFile,
            workDir = temporaryDir,
        )
    }
}
