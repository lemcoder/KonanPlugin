package io.github.lemcoder.index

import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.javaExecCapture
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
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * Indexes the headers a `.def` names with cinterop's own indexer and runs a [generator] over the
 * resulting `NativeIndex`, in the exporter's process, writing into [outputDirectory].
 *
 * The indexer runs in a JVM of its own whose classpath is only the distribution's compiler jar and the
 * exporter. Not in the daemon: a native library such as libclang can be loaded by one classloader per
 * JVM, and KGP's cinterop may already have loaded it there. Not in a Gradle worker either: that process
 * carries Gradle's Kotlin stdlib and the plugin's classes, which would meet the stdlib and the model
 * classes bundled in the compiler jar.
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

    /** A generator the exporter knows by name, e.g. `dump`. */
    @get:Input
    abstract val generator: Property<String>

    /** Replaced on every run with the generator's output. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun export() {
        val home = konanHome.orNull?.asFile ?: PinnedKotlinNative.home() ?: error(
            "Kotlin/Native ${PinnedKotlinNative.version} is not installed; the native index needs exactly that version."
        )
        val compilerJar = home.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
        check(compilerJar.isFile) { "Not a Kotlin/Native distribution: $home" }

        val arguments = buildList {
            add("--generator"); add(generator.get())
            add("--output"); add(outputDirectory.get().asFile.absolutePath)
            // From here on, a cinterop command line.
            add("--")
            add("-def"); add(defFile.get().asFile.absolutePath)
            add("-target"); add(target.get().name)
            add("-Xkonan-home"); add(home.absolutePath)
            includeDirs.files.forEach { add("-compiler-option"); add("-I${it.absolutePath}") }
            compilerOptions.getOrElse(emptyList()).forEach { add("-compiler-option"); add(it) }
        }
        val exporterJar = temporaryDir.resolve(EXPORTER_JAR)
        javaClass.getResourceAsStream("/io/github/lemcoder/index/$EXPORTER_JAR").use { input ->
            checkNotNull(input) { "Index exporter missing from the plugin jar: $EXPORTER_JAR" }
            Files.copy(input, exporterJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val result = exec.javaExecCapture {
            classpath(compilerJar, exporterJar)
            mainClass.set(EXPORTER_MAIN)
            args(arguments)
            systemProperty("konan.home", home.absolutePath)
            systemProperty("java.library.path", home.resolve("konan/nativelib").absolutePath)
            environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
        }
        check(result is ExecCaptureResult.Success) { "Indexing ${defFile.get().asFile.name} failed:\n${result.output}" }
    }

    private companion object {
        const val EXPORTER_JAR = "native-index-exporter.jar"
        const val EXPORTER_MAIN = "io.github.lemcoder.index.exporter.NativeIndexExporterKt"
    }
}
