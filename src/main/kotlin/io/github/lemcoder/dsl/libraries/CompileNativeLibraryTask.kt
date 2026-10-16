package io.github.lemcoder.dsl.libraries

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
 * Compile C/C++ source files using the
 * [`run_konan`](https://github.com/JetBrains/kotlin/blob/v1.9.0/kotlin-native/HACKING.md#running-clang-the-same-way-kotlinnative-compiler-does)
 * utility.
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

    /** The Kotlin/Native distribution whose clang compiles. */
    @get:Internal abstract override val konanHome: Property<ConfigValue>

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
        val distribution = distribution()
        targets.get().forEach { target ->
            distribution.ensureDependencies(target, exec, temporaryDir.resolve("dependencies"))
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
                commandLine(distribution.clang(target, listOf("@args")))
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
                commandLine(distribution.llvmAr(listOf("@args")))
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