package io.github.lemcoder.interop

import io.github.lemcoder.util.execCapture
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/** Configures the external CMake build that compiles the generated JNI stub. */
abstract class CMakeConfigureTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val executable: Property<String>
    @get:Input @get:Optional abstract val preset: Property<String>
    /** Also the working directory: `--preset` resolves CMakePresets.json relative to the cwd. */
    @get:Input abstract val sourceDirectory: Property<String>
    @get:Input abstract val arguments: ListProperty<String>

    /** `-D` entries the plugin supplies: where the stub is, and what the library must be called. */
    @get:Input abstract val cacheEntries: MapProperty<String, String>

    /** A JDK that ships `include/jni.h`; CMake's FindJNI wants a full JDK, the stub only the header. */
    @get:Input abstract val javaHome: Property<String>

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stubSourceDirectory: DirectoryProperty

    @get:OutputDirectory abstract val buildDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val build = buildDirectory.get().asFile.apply { mkdirs() }
        val cmd = buildList {
            add(executable.get())
            preset.orNull?.let {
                // A preset carries its own binaryDir, which -B may not override.
                add("--preset"); add(it)
            } ?: run {
                add("-S"); add(sourceDirectory.get())
                add("-B"); add(build.absolutePath)
            }
            cacheEntries.get().forEach { (key, value) -> add("-D$key=$value") }
            addAll(arguments.get())
        }

        val result = exec.execCapture {
            commandLine(cmd)
            workingDir = File(sourceDirectory.get())
            environment("JAVA_HOME", javaHome.get())
        }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
    }
}

/** Runs the external CMake build for the generated JNI stub. */
abstract class CMakeBuildTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val targets: ListProperty<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stubSourceDirectory: DirectoryProperty
    @get:Internal abstract val buildDirectory: DirectoryProperty

    /** Where CMake was told to leave the linked library. */
    @get:OutputDirectory abstract val libraryDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val cmd = buildList {
            add(executable.get())
            add("--build"); add(buildDirectory.get().asFile.absolutePath)
            targets.get().forEach { add("--target"); add(it) }
            add("-j"); add(Runtime.getRuntime().availableProcessors().toString())
        }

        val result = exec.execCapture { commandLine(cmd) }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
    }
}

internal object CMakeSupport {
    /**
     * The `cmake` binary. The Gradle daemon does not inherit a login shell's PATH, so a Homebrew or
     * MacPorts install is invisible to it and a bare `cmake` fails with "A problem occurred starting
     * process".
     */
    fun detectExecutable(): String = System.getenv("CMAKE")
        ?: sequenceOf(
            "/opt/homebrew/bin/cmake",
            "/usr/local/bin/cmake",
            "/usr/bin/cmake",
            "/opt/local/bin/cmake",
        ).firstOrNull { File(it).canExecute() }
        ?: "cmake"
}
