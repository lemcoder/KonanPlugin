package io.github.lemcoder.library

import io.github.lemcoder.dsl.ZigOptimize
import io.github.lemcoder.toolchain.zigTriple
import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.execCapture
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

/**
 * Builds a library with the project's own `build.zig`, once per target, and puts each static archive
 * where every other provider puts it: `<outputDirectory>/<target>/lib<libraryName>.a`. Consumers never
 * learn which build system made it.
 */
@DisableCachingByDefault(because = "Output depends on the local Zig installation, which is not a tracked input")
internal abstract class ZigBuildTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    /** The directory holding `build.zig`; its `zig build` runs there. */
    @get:Internal abstract val projectDirectory: DirectoryProperty

    /** What the build reads: the project directory, less Zig's own cache and output. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection

    @get:Input abstract val executable: Property<String>
    @get:Input abstract val targets: ListProperty<KonanTarget>
    @get:Input abstract val optimize: Property<ZigOptimize>
    @get:Input abstract val arguments: ListProperty<String>

    /** The static library `build.zig` installs, by its Zig name. */
    @get:Input abstract val artifactName: Property<String>

    /** The name consumers know the library by: the archive becomes `lib<libraryName>.a`. */
    @get:Input abstract val libraryName: Property<String>

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val artifact = artifactName.get()
        // Read before exec's block: inside it, `executable` is the ExecSpec's own.
        val zig = executable.get()
        val directory = projectDirectory.get().asFile
        val extra = arguments.get()
        targets.get().forEach { target ->
            val prefix = temporaryDir.resolve("install/${target.name}").apply { deleteRecursively() }
            val result = exec.execCapture {
                workingDir(directory)
                commandLine(
                    listOf(
                        zig, "build",
                        "--prefix", prefix.absolutePath,
                        // Keep Zig's cache out of the project directory, which is this task's input.
                        "--cache-dir", temporaryDir.resolve("cache").absolutePath,
                        "-Dtarget=${target.zigTriple()}",
                        "-Doptimize=${optimize.get().zigName}",
                    ) + extra
                )
            }
            check(result is ExecCaptureResult.Success) { "zig build for ${target.name} failed:\n${result.output}" }

            // Zig names the archive after the target's convention: libfoo.a, or foo.lib on Windows.
            val built = listOf("lib/lib$artifact.a", "lib/$artifact.lib").map(prefix::resolve).firstOrNull { it.isFile }
                ?: error("zig build for ${target.name} installed no static library '$artifact' under $prefix/lib")
            val archive = outputDirectory.get().asFile.resolve(NativeArtifacts.archivePath(libraryName.get(), target))
            archive.parentFile.mkdirs()
            built.copyTo(archive, overwrite = true)
        }
    }
}
