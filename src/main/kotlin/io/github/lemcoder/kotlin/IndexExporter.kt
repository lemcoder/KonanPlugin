package io.github.lemcoder.kotlin

import io.github.lemcoder.index.ExporterInvocation
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.javaExecCapture
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runs the index exporter: cinterop's own indexer over [cinteropArgs], then the generator [request] asks
 * for, writing into [outputDirectory].
 *
 * It runs in a JVM of its own whose classpath is only the distribution's compiler jar and the exporter.
 * Not in the daemon: a native library such as libclang can be loaded by one classloader per JVM, and KGP's
 * cinterop may already have loaded it there. Not in a Gradle worker either: that process carries Gradle's
 * Kotlin stdlib, which would meet the one bundled in the compiler jar.
 *
 * @param konanHome the distribution to run in; by default the installed one of [KonanDistribution.pinnedVersion].
 * @param workDir where the exporter jar is extracted, e.g. the task's temporary directory.
 */
internal fun ExecOperations.runIndexExporter(
    request: GeneratorRequest,
    outputDirectory: File,
    cinteropArgs: List<String>,
    konanHome: File? = null,
    workDir: File,
) {
    val distribution = konanHome?.let(::KonanDistribution) ?: KonanDistribution.pinned() ?: error(
        "Kotlin/Native ${KonanDistribution.pinnedVersion} is not installed; generating bindings needs exactly that " +
            "version. A Kotlin Multiplatform build of ${KonanDistribution.pinnedVersion} with a native target downloads it."
    )
    val home = distribution.home
    check(distribution.compilerJar.isFile) { "Not a Kotlin/Native distribution: $home" }

    val exporterJar = workDir.resolve(EXPORTER_JAR)
    workDir.mkdirs()
    KonanDistribution::class.java.getResourceAsStream("/io/github/lemcoder/index/$EXPORTER_JAR").use { input ->
        checkNotNull(input) { "Index exporter missing from the plugin jar: $EXPORTER_JAR" }
        Files.copy(input, exporterJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    val invocation = ExporterInvocation(request, outputDirectory, listOf("-Xkonan-home", home.absolutePath) + cinteropArgs)
    val result = javaExecCapture {
        classpath(distribution.compilerJar, exporterJar)
        mainClass.set(EXPORTER_MAIN)
        args(invocation.toArgs())
        systemProperty("konan.home", home.absolutePath)
        systemProperty("java.library.path", distribution.nativeLibraryDirectory.absolutePath)
        environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
    }
    check(result is ExecCaptureResult.Success) { "Generating bindings ($request) failed:\n${result.output}" }
}

private const val EXPORTER_JAR = "native-index-exporter.jar"
private const val EXPORTER_MAIN = "io.github.lemcoder.index.exporter.NativeIndexExporterKt"
