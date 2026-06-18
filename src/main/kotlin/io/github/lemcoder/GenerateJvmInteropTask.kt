package io.github.lemcoder

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity.NAME_ONLY
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Generate JVM (JNI) bindings for a C library from a `.def` file using the **JVM flavor** of
 * Kotlin/Native's `cinterop` stub generator.
 *
 * This drives the internal entry point
 * `org.jetbrains.kotlin.native.interop.gen.jvm.MainKt -flavor jvm`,
 * which is bundled in `konan/lib/kotlin-native-compiler-embeddable.jar` of every Kotlin/Native
 * distribution. The shipped `cinterop` command-line tool hard-codes `flavor == "native"` and cannot
 * produce JNI, so we invoke the generator's main class directly.
 *
 * Two artifacts are produced under [outputDirectory]:
 *  - `kotlin/<package>/<name>.kt`  - Kotlin `external fun kniBridgeN(...)` declarations plus friendly
 *    wrappers; calls `kotlinx.cinterop.loadKonanLibrary("<name>stubs")` to load the native library.
 *  - `c/<name>stubs.c`             - `JNIEXPORT ... JNICALL Java_<pkg>_<class>_kniBridgeN(...)` bridges.
 *
 * The `.c` file must then be compiled and linked (together with the implementation of the C library)
 * into a shared library named `lib<name>stubs.{so,dylib,dll}`, and the `.kt` file must be compiled on
 * the JVM with `kotlinx.cinterop` (from the same embeddable jar) on the classpath.
 *
 * NOTE: the JVM/JNI flavor is internal, unsupported tooling. Output shape and the runtime contract may
 * change between Kotlin/Native versions.
 */
abstract class GenerateJvmInteropTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {

    /** Root of the Kotlin/Native distribution (same value as `konanConfig.konanPath`). */
    @get:Input
    abstract val konanPath: Property<String>

    /** The `.def` file describing the headers/package to generate bindings for. */
    @get:InputFile
    @get:PathSensitive(NAME_ONLY) // package is derived from the def file name when not set in the def
    abstract val defFile: RegularFileProperty

    /** Directories searched for the C headers referenced by the `.def` file (passed as `-I`). */
    @get:InputFiles
    @get:PathSensitive(RELATIVE)
    abstract val headerDirs: ConfigurableFileCollection

    /**
     * JDK directories that contain `jni.h` / `jni_md.h` (passed as `-I`).
     * Defaults to the `include` dir of the JVM running Gradle and its OS-specific subdirectory.
     */
    @get:InputFiles
    @get:PathSensitive(NAME_ONLY)
    abstract val jdkIncludeDirs: ConfigurableFileCollection

    /** Kotlin/Native target the bindings are generated for, e.g. `macos_arm64`, `linux_x64`, `mingw_x64`. */
    @get:Input
    abstract val target: Property<String>

    /** Extra options forwarded to the underlying clang invocation as `-compiler-option <opt>`. */
    @get:Input
    @get:Optional
    abstract val additionalCompilerArgs: ListProperty<String>

    /** Output directory; receives `kotlin/<package>/<name>.kt` and `c/<name>stubs.c`. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val konanHome = File(konanPath.get())
        val embeddableJar = konanHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
        check(embeddableJar.isFile) { "Embeddable compiler jar not found: $embeddableJar" }
        val nativeLibDir = konanHome.resolve("konan/nativelib")
        check(nativeLibDir.isDirectory) { "Konan native libraries not found: $nativeLibDir" }

        val out = outputDirectory.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val includeOpts = (headerDirs.files + jdkIncludeDirs.files)
            .filter { it.exists() }
            .flatMap { listOf("-compiler-option", "-I${it.absolutePath}") }
        val extraOpts = additionalCompilerArgs.getOrElse(emptyList())
            .flatMap { listOf("-compiler-option", it) }

        val result = exec.execCapture {
            executable("java")
            args(
                "-ea",
                "-Dkonan.home=${konanHome.absolutePath}",
                "-Djava.library.path=${nativeLibDir.absolutePath}",
                "-cp", embeddableJar.absolutePath,
                "org.jetbrains.kotlin.native.interop.gen.jvm.MainKt",
                "-flavor", "jvm",
                "-def", defFile.get().asFile.absolutePath,
                "-generated", out.resolve("kotlin").absolutePath,
                "-Xtemporary-files-dir", out.resolve("c").absolutePath,
                "-target", target.get(),
            )
            args(includeOpts)
            args(extraOpts)
            // libclang crash-recovery interferes with running inside another JVM
            environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
        }

        logger.lifecycle(result.output)
        result.assertNormalExitValue()
    }
}
