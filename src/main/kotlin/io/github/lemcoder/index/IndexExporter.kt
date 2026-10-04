package io.github.lemcoder.index

import io.github.lemcoder.util.ExecCaptureResult
import io.github.lemcoder.util.javaExecCapture
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runs the index exporter: cinterop's own indexer over [cinteropArgs], then [generator] over the index,
 * writing into [outputDirectory].
 *
 * It runs in a JVM of its own whose classpath is only the distribution's compiler jar and the exporter.
 * Not in the daemon: a native library such as libclang can be loaded by one classloader per JVM, and KGP's
 * cinterop may already have loaded it there. Not in a Gradle worker either: that process carries Gradle's
 * Kotlin stdlib, which would meet the one bundled in the compiler jar.
 *
 * @param konanHome the distribution to run in; by default the installed one of [PinnedKotlinNative.version].
 * @param workDir where the exporter jar is extracted, e.g. the task's temporary directory.
 */
internal fun ExecOperations.runIndexExporter(
    generator: String,
    outputDirectory: File,
    cinteropArgs: List<String>,
    generatorOptions: Map<String, String> = emptyMap(),
    konanHome: File? = null,
    workDir: File,
) {
    val home = konanHome ?: PinnedKotlinNative.home() ?: error(
        "Kotlin/Native ${PinnedKotlinNative.version} is not installed; generating bindings needs exactly that " +
            "version. A Kotlin Multiplatform build of ${PinnedKotlinNative.version} with a native target downloads it."
    )
    val compilerJar = home.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
    check(compilerJar.isFile) { "Not a Kotlin/Native distribution: $home" }

    val exporterJar = workDir.resolve(EXPORTER_JAR)
    workDir.mkdirs()
    PinnedKotlinNative::class.java.getResourceAsStream("/io/github/lemcoder/index/$EXPORTER_JAR").use { input ->
        checkNotNull(input) { "Index exporter missing from the plugin jar: $EXPORTER_JAR" }
        Files.copy(input, exporterJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    val arguments = buildList {
        add("--generator"); add(generator)
        add("--output"); add(outputDirectory.absolutePath)
        generatorOptions.forEach { (key, value) -> add("--option"); add("$key=$value") }
        // From here on, a cinterop command line.
        add("--")
        add("-Xkonan-home"); add(home.absolutePath)
        addAll(cinteropArgs)
    }
    val result = javaExecCapture {
        classpath(compilerJar, exporterJar)
        mainClass.set(EXPORTER_MAIN)
        args(arguments)
        systemProperty("konan.home", home.absolutePath)
        systemProperty("java.library.path", home.resolve("konan/nativelib").absolutePath)
        environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
    }
    check(result is ExecCaptureResult.Success) { "Generating $generator bindings failed:\n${result.output}" }
}

private const val EXPORTER_JAR = "native-index-exporter.jar"
private const val EXPORTER_MAIN = "io.github.lemcoder.index.exporter.NativeIndexExporterKt"
