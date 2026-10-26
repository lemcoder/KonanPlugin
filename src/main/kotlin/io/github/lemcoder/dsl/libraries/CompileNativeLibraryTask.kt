package io.github.lemcoder.dsl.libraries

import io.github.lemcoder.android.AndroidNdk
import io.github.lemcoder.dsl.config.ConfigValue
import io.github.lemcoder.kotlin.UsesKonanDistribution
import io.github.lemcoder.kotlin.distribution
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.util.execCapture
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Compiles C/C++ sources and archives them for one [target]: with Kotlin/Native's clang and llvm-ar through
 * [`run_konan`](https://github.com/JetBrains/kotlin/blob/v1.9.0/kotlin-native/HACKING.md#running-clang-the-same-way-kotlinnative-compiler-does),
 * and for an Android target with the NDK's — [androidNdk], required then. One task per target, so a
 * consumer of one target builds only its archive.
 */
@DisableCachingByDefault(because = "Output depends on the local Kotlin/Native toolchain, which is not a tracked input")
internal abstract class CompileNativeLibraryTask @Inject constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
) : DefaultTask(), UsesKonanDistribution {

    /** C and C++ source files to compile to object files */
    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** Directories that include `.h` header files */
    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    /** The Kotlin/Native distribution whose clang compiles every target but Android's. */
    @get:Internal abstract override val konanHome: Property<ConfigValue>

    /** The Android NDK whose clang compiles an Android target; set when this is one. */
    @get:Internal abstract val androidNdk: Property<ConfigValue>

    /** Which NDK — path and revision — so another one recompiles. */
    @get:Input @get:Optional
    val androidNdkIdentity: Provider<String> get() = androidNdk.map { AndroidNdk.identity(it) }

    /** The API level an Android target compiles for. */
    @get:Input @get:Optional abstract val androidApiLevel: Property<Int>

    @get:Input abstract val target: Property<NativeTarget>

    @get:Input
    @get:Optional
    abstract val arguments: ListProperty<String>

    @get:Input
    abstract val libName: Property<String>

    /** Where the archive goes: `lib<name>.a`, and nothing else. */
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun compileAndArchive() {
        val target = target.get()
        val clang: (List<String>) -> List<String>
        val llvmAr: (List<String>) -> List<String>
        if (target.isAndroid) {
            val ndk = AndroidNdk.of(androidNdk.get())
            val clangPath = ndk.clang(target, androidApiLevel.getOrElse(AndroidNdk.DEFAULT_API_LEVEL)).absolutePath
            clang = { args -> listOf(clangPath) + args }
            llvmAr = { args -> listOf(ndk.llvmAr.absolutePath) + args }
        } else {
            val distribution = distribution()
            distribution.ensureDependencies(target, exec, temporaryDir.resolve("dependencies"))
            clang = { args -> distribution.clang(target, args) }
            llvmAr = distribution::llvmAr
        }

        val work = temporaryDir
        val sourcesDir = work.resolve("sources")
        val headersDir = work.resolve("headers")
        val compileDir = work.resolve("compile").apply { deleteRecursively(); mkdirs() }
        val archiveDir = work.resolve("archive").apply { deleteRecursively(); mkdirs() }
        fs.sync { from(sourceFiles); into(sourcesDir) }
        fs.sync { from(includeDirs); into(headersDir) }

        val sourcePaths = sourcesDir.walk().filter { it.extension in listOf("cpp", "c") }.joinToString("\n") { it.invariantSeparatorsPath }
        compileDir.resolve("args").writeText(
            "--include-directory ${headersDir.invariantSeparatorsPath}\n" +
                arguments.getOrElse(emptyList()).joinToString("\n") + "\n-c $sourcePaths\n"
        )
        run(clang(listOf("@args")), compileDir)

        val objectPaths = compileDir.walk().filter { it.extension == "o" }.joinToString("\n") { it.invariantSeparatorsPath }
        val archiveName = NativeArtifacts.archiveFileName(libName.get())
        archiveDir.resolve("args").writeText("-rv\n$archiveName\n$objectPaths\n")
        run(llvmAr(listOf("@args")), archiveDir)

        fs.sync { from(archiveDir.resolve(archiveName)); into(outputDirectory) }
    }

    private fun run(command: List<String>, directory: java.io.File) {
        val result = exec.execCapture {
            commandLine(command)
            workingDir(directory)
        }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
    }
}
