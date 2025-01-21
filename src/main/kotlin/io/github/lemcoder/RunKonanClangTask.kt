package io.github.lemcoder


import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.PathSensitivity.NAME_ONLY
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.util.parseSpaceSeparatedArgs
import java.io.File
import javax.inject.Inject


/**
 * https://github.com/JetBrains/kotlin/blob/v1.9.0/kotlin-native/HACKING.md#running-clang-the-same-way-kotlinnative-compiler-does
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

    @get:Internal
    abstract val workingDir: DirectoryProperty

    @get:Input
    abstract val libType: Property<LibraryType>

    @get:Input
    abstract val libName: Property<String>

    @TaskAction
    fun compileAndLink() {
        val workingDir = workingDir.asFile.getOrElse(temporaryDir)
        targets.get().forEach { kotlinTarget ->
            // prepare output dirs
            val sourcesDir: File = workingDir.resolve(SOURCES_DIR)
            val headersDir: File = workingDir.resolve(HEADERS_DIR)
            val compileDir: File = workingDir.resolve(COMPILE_DIR)

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

            // prepare commands
            val commands = CommandBuilder(
                libType = libType.get(),
                target = kotlinTarget,
                workingDir = workingDir,
                libFileName = libName.get()
            ).build()

            commands.forEach { command ->
                logger.lifecycle("Running: $command")
                // run command
                val compileResult = exec.execCapture {
                    executable(runKonan.asFile.get())
                    args(command().split(" "))
                    workingDir(compileDir)
                }

                // verify output
                logger.lifecycle(compileResult.output)
                compileResult.assertNormalExitValue()
            }

            val outDir = outputDir.get().dir(kotlinTarget.name)
            fs.delete { delete(outDir) }
            outDir.asFile.mkdirs()

            fs.sync {
                from(compileDir) {
                    include("*.${getFileExtension(libType.get(), kotlinTarget)}")
                }
                into(outDir)
            }
        }
    }
}