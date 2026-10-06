package io.github.lemcoder.external

import io.github.lemcoder.model.Executable
import io.github.lemcoder.toolchain.command
import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.execCapture
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
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
 * Runs a build system the plugin does not model, for one target: its [commands] in order, with the
 * plugin's contract in [environment], then — if the build leaves its library somewhere of its own —
 * copies [producedLibrary] into [outputDirectory] as [libraryFileName].
 *
 * Up-to-date only when [sources] are declared: without them the plugin cannot know what the build
 * reads, so it always runs and the build's own incrementality decides.
 */
@DisableCachingByDefault(because = "Runs a build system whose inputs the plugin does not model")
internal abstract class ExternalBuildTask @Inject constructor(
    private val exec: ExecOperations,
    private val objects: ObjectFactory,
) : DefaultTask() {
    @get:Nested val commands: MutableList<ExternalCommand> = mutableListOf()

    /** Every command's environment: the plugin's contract. A command's own entries win. */
    @get:Input abstract val environment: MapProperty<String, String>

    /** What the build reads, as declared by the build script. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection

    /** What the build reads that the plugin generated, such as the JNI stub. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val generatedSources: ConfigurableFileCollection

    /** Where the build leaves its library, when that is not [outputDirectory]. */
    @get:Internal abstract val producedLibrary: RegularFileProperty

    /** The name the library must have in [outputDirectory]. */
    @get:Input abstract val libraryFileName: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { !(it as ExternalBuildTask).sources.isEmpty }
    }

    fun command(configure: ExternalCommand.() -> Unit) {
        commands += objects.newInstance(ExternalCommand::class.java).apply(configure)
    }

    @TaskAction
    fun run() {
        outputDirectory.get().asFile.mkdirs()
        // Read before exec's block: inside it, `environment` is the ExecSpec's own.
        val contract = environment.get()
        commands.forEachIndexed { index, command ->
            val line = listOf(command.executable.get().command()) + command.arguments.get()
            val directory = command.workingDirectory.orNull
            val variables = contract + command.environment.get()
            val result = exec.execCapture {
                commandLine(line)
                directory?.let { workingDir = File(it) }
                environment(variables)
            }
            logger.info(result.output)
            check(result is ExecCaptureResult.Success) {
                "Command ${index + 1} of $path failed: ${line.joinToString(" ")}\n${result.output}"
            }
        }
        producedLibrary.orNull?.asFile?.let { produced ->
            check(produced.isFile) { "$path: the build left no library at $produced" }
            produced.copyTo(outputDirectory.get().asFile.resolve(libraryFileName.get()), overwrite = true)
        }
    }
}

/** One command of an external build. */
internal abstract class ExternalCommand {
    @get:Input abstract val executable: Property<Executable>
    @get:Input abstract val arguments: ListProperty<String>
    @get:Input abstract val environment: MapProperty<String, String>

    /** A path, not a tracked directory: the build's own directory is rarely only its input. */
    @get:Input @get:Optional abstract val workingDirectory: Property<String>
}
