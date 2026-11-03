package io.github.lemcoder.kotlin

import io.github.lemcoder.index.ExporterInvocation
import io.github.lemcoder.index.GeneratorRequest
import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.javaExecCapture
import org.gradle.api.InvalidUserDataException
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
 * The plugin carries one exporter per Kotlin/Native version, each compiled against that version's compiler,
 * and runs the one matching [distribution]; a version it has none for is an error.
 *
 * @param distribution the distribution to run in, one of [KonanDistribution.supportedVersions].
 * @param workDir where the exporter jar is extracted, e.g. the task's temporary directory.
 */
internal fun ExecOperations.runIndexExporter(
    request: GeneratorRequest,
    outputDirectory: File,
    cinteropArgs: List<String>,
    distribution: KonanDistribution,
    workDir: File,
) {
    val home = distribution.home
    check(distribution.compilerJar.isFile) { "Not a Kotlin/Native distribution: $home" }

    val version = distribution.version
    if (version !in KonanDistribution.supportedVersions) throw unsupported(distribution)
    val jarName = "native-index-exporter-$version.jar"
    val exporterJar = workDir.resolve(jarName)
    workDir.mkdirs()
    KonanDistribution::class.java.getResourceAsStream("/io/github/lemcoder/index/exporters/$jarName").use { input ->
        checkNotNull(input) { "Index exporter missing from the plugin jar: $jarName" }
        Files.copy(input, exporterJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    val invocation = ExporterInvocation(request, outputDirectory, cinteropArgs)
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

private fun unsupported(distribution: KonanDistribution) = InvalidUserDataException(
    buildString {
        appendLine("konan.config.konanHome ${distribution.description} is Kotlin/Native ${distribution.version}, which this plugin cannot generate bindings with.")
        appendLine("Point it at one of the versions it supports:")
        KonanDistribution.supportedVersions.groupBy { it.substringBeforeLast('.') }.values
            .forEach { appendLine("  " + it.joinToString(", ")) }
        append("Its default location is ${KonanDistribution.defaultHome("<version>")}.")
    }
)

private const val EXPORTER_MAIN = "io.github.lemcoder.index.exporter.NativeIndexExporterKt"
