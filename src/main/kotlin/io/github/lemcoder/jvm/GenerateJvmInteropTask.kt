package io.github.lemcoder.jvm

import io.github.lemcoder.KonanTarget
import io.github.lemcoder.util.ParamKind
import io.github.lemcoder.util.execCapture
import io.github.lemcoder.util.marshalStub
import io.github.lemcoder.util.parseBridgeKinds
import io.github.lemcoder.util.stripCinterop
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/** Generates the JNI `.c` stubs and the stripped, runtime-free Kotlin bindings. */
abstract class GenerateJvmInteropTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val konanPath: Property<String>
    @get:Input abstract val headers: ListProperty<String>
    @get:Input abstract val packageName: Property<String>
    @get:Input @get:Optional abstract val headerFilter: Property<String>
    @get:Input abstract val headerDir: Property<String>
    @get:Input abstract val hostTarget: Property<KonanTarget>
    @get:Input abstract val jniIncludeDirs: ListProperty<String>
    @get:Input @get:Optional abstract val additionalCompilerArgs: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val konanHome = File(konanPath.get())
        val embeddableJar = konanHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
        check(embeddableJar.isFile) { "Embeddable compiler jar not found: $embeddableJar" }
        val nativeLibDir = konanHome.resolve("konan/nativelib")

        val out = outputDirectory.get().asFile
        out.deleteRecursively(); out.mkdirs()

        // Synthesize the .def from the configured headers + package.
        val defFile = out.resolve("${JvmInteropSupport.stubBaseName(packageName.get())}.def")
        defFile.writeText(buildString {
            appendLine("headers = ${headers.get().joinToString(" ")}")
            appendLine("headerFilter = ${headerFilter.orNull ?: headers.get().joinToString(" ")}")
            appendLine("package = ${packageName.get()}")
        })

        val includeOpts = (listOf(headerDir.get()) + jniIncludeDirs.get())
            .map { File(it) }.filter { it.exists() }
            .flatMap { listOf("-compiler-option", "-I${it.absolutePath}") }
        val extraOpts = additionalCompilerArgs.getOrElse(emptyList()).flatMap { listOf("-compiler-option", it) }

        val result = exec.execCapture {
            executable("java")
            args(
                "-ea",
                "-Dkonan.home=${konanHome.absolutePath}",
                "-Djava.library.path=${nativeLibDir.absolutePath}",
                "-cp", embeddableJar.absolutePath,
                "org.jetbrains.kotlin.native.interop.gen.jvm.MainKt",
                "-flavor", "jvm",
                "-def", defFile.absolutePath,
                "-generated", out.resolve("kotlin").absolutePath,
                "-Xtemporary-files-dir", out.resolve("c").absolutePath,
                "-target", hostTarget.get().konanName,
            )
            args(includeOpts); args(extraOpts)
            environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
        }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()

        // Strip kotlinx.cinterop from the generated Kotlin so it ships on plain JVM / Android, and
        // marshal strings/primitive buffers on both sides of the bridge — the raw output passes them
        // as addresses, which only a Kotlin/Native caller can produce.
        val kotlinFiles = out.resolve("kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        val kinds = kotlinFiles.fold(emptyMap<Int, List<ParamKind>>()) { acc, kt ->
            acc + parseBridgeKinds(kt.readText())
        }
        kotlinFiles.forEach { kt -> kt.writeText(stripCinterop(kt.readText(), kinds)) }
        out.resolve("c").walkTopDown().filter { it.extension == "c" }
            .forEach { c -> c.writeText(marshalStub(c.readText(), kinds)) }
    }
}