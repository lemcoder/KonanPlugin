package io.github.lemcoder


import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NAME_ONLY
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.util.parseSpaceSeparatedArgs
import javax.inject.Inject

/**
 * Compile C/C++ source files using the
 * [`run_konan`](https://github.com/JetBrains/kotlin/blob/v1.9.0/kotlin-native/HACKING.md#running-clang-the-same-way-kotlinnative-compiler-does)
 * utility.
 */
abstract class RunKonanClangTask @Inject constructor(
    private val exec: ExecOperations,
    private val fs: FileSystemOperations,
    private val objects: ObjectFactory,
) : DefaultTask() {

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

    /** Path to the (platform specific) `run_konan` utility */
    @get:InputFile
    @get:PathSensitive(NAME_ONLY)
    abstract val runKonan: RegularFileProperty

    /** Kotlin target platform, e.g. `mingw_x64` */
    @get:Input
    abstract val targets: ListProperty<KonanTarget>

    @get:Input
    @get:Optional
    abstract val arguments: ListProperty<String>

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:Input
    abstract val libName: Property<String>

    private val libFileName: Provider<String>
        get() = libName.map { "lib${it}.a" }

    @TaskAction
    fun compileAndLink() {
        val workingDir = workingDir.asFile.getOrElse(temporaryDir)
        targets.get().forEach { kotlinTarget ->
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
                executable(runKonan.asFile.get())
                args(
                    parseSpaceSeparatedArgs(
                        "clang clang $kotlinTarget @args"
                    )
                )
                workingDir(compileDir)
            }

            // verify output
            logger.lifecycle(compileResult.output)
            compileResult.assertNormalExitValue()

            // move compiled files to output directory
            val outDir = outputDir.get().dir(kotlinTarget.name)
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
                executable(runKonan.asFile.get())
                args(
                    parseSpaceSeparatedArgs(
                        "llvm llvm-ar @args"
                    )
                )
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