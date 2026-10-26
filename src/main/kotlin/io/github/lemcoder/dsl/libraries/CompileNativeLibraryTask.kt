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
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Compiles C/C++ sources and archives them, per target: with Kotlin/Native's clang and llvm-ar through
 * [`run_konan`](https://github.com/JetBrains/kotlin/blob/v1.9.0/kotlin-native/HACKING.md#running-clang-the-same-way-kotlinnative-compiler-does),
 * and for Android targets with the NDK's — [androidNdk], required then.
 */
@DisableCachingByDefault(because = "Output depends on the local Kotlin/Native toolchain, which is not a tracked input")
internal abstract class CompileNativeLibraryTask @Inject constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
    private val objects: ObjectFactory,
) : DefaultTask(), UsesKonanDistribution {

    /** Destination of compiled `.o` object files */
    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val outputDir: DirectoryProperty

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

    /** The Android NDK whose clang compiles Android targets; set when there is one. */
    @get:Internal abstract val androidNdk: Property<ConfigValue>

    /** Which NDK — path and revision — so another one recompiles. */
    @get:Input @get:Optional
    val androidNdkIdentity: Provider<String> get() = androidNdk.map { AndroidNdk.identity(it) }

    /** The API level Android targets compile for. */
    @get:Input @get:Optional abstract val androidApiLevel: Property<Int>

    /** Targets to compile for */
    @get:Input
    abstract val targets: ListProperty<NativeTarget>

    @get:Input
    @get:Optional
    abstract val arguments: ListProperty<String>

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:Input
    abstract val libName: Property<String>

    private val libFileName: Provider<String>
        get() = libName.map { NativeArtifacts.archiveFileName(it) }

    @TaskAction
    fun compileAndLink() {
        val workingDir = workingDir.asFile.getOrElse(temporaryDir)
        // Each only when a target needs it: an Android-only library needs no Kotlin/Native, a host one no NDK.
        val distribution by lazy { distribution() }
        val ndk by lazy { AndroidNdk.of(androidNdk.get()) }
        val apiLevel = androidApiLevel.getOrElse(AndroidNdk.DEFAULT_API_LEVEL)
        targets.get().forEach { target ->
            val clang: (List<String>) -> List<String> =
                if (target.isAndroid) { args -> listOf(ndk.clang(target, apiLevel).absolutePath) + args } else { args -> distribution.clang(target, args) }
            val llvmAr: (List<String>) -> List<String> =
                if (target.isAndroid) { args -> listOf(ndk.llvmAr.absolutePath) + args } else distribution::llvmAr
            if (!target.isAndroid) distribution.ensureDependencies(target, exec, temporaryDir.resolve("dependencies"))
            // prepare output dirs
            val sourcesDir = workingDir.resolve("sources")
            val headersDir = workingDir.resolve("headers")
            val compileDir = workingDir.resolve("compile")
            val linkDir = workingDir.resolve("link")

            fs.sync {
                from(sourceFiles)
                into(sourcesDir)
            }

            fs.sync {
                from(includeDirs)
                into(headersDir)
            }

            fs.delete { delete(compileDir) }
            compileDir.mkdirs()

            fs.delete { delete(linkDir) }
            linkDir.mkdirs()

            // ---
            // Compiling

            // prepare args file
            val sourceFilePaths = sourcesDir.walk()
                .filter { it.extension in listOf("cpp", "c") }
                .joinToString("\n") { it.invariantSeparatorsPath }

            compileDir.resolve("args")
                .writeText(/*language=text*/
                    """
                    |--include-directory ${headersDir.invariantSeparatorsPath}
                    |${arguments.getOrElse(emptyList()).joinToString("\n")}
                    |-c $sourceFilePaths
                    """.trimMargin()
                )

            // compile files
            val compileResult = exec.execCapture {
                commandLine(clang(listOf("@args")))
                workingDir(compileDir)
            }

            // verify output
            logger.lifecycle(compileResult.output)
            compileResult.assertNormalExitValue()

            // move compiled files to output directory
            val outDir = outputDir.get().dir(target.konanName)
            fs.delete { delete(outDir) }
            fs.sync {
                from(compileDir) {
                    include("**/*.o")
                }
                into(outDir)
            }

            // ---
            // Linking

            val objFilesPaths = outDir
                .asFileTree
                .matching { include("**/*.o") }
                .joinToString("\n") { it.invariantSeparatorsPath }

            linkDir.resolve("args")
                .writeText(
                    /*language=text*/ """
                    |-rv
                    |${libFileName.get()}
                    |$objFilesPaths
                    """.trimMargin()
                )

            // link files
            val linkResult = exec.execCapture {
                commandLine(llvmAr(listOf("@args")))
                workingDir(linkDir)
            }

            logger.lifecycle(linkResult.output)
            linkResult.assertNormalExitValue()

            fs.delete { delete(outDir) }
            fs.sync {
                from(linkDir) {
                    include("**/*.a")
                }
                into(outDir)
            }

        }
    }
}