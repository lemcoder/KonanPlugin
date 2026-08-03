package io.github.lemcoder.jvm

import org.jetbrains.kotlin.konan.target.KonanTarget
import io.github.lemcoder.interop.DefFile
import io.github.lemcoder.util.ParamKind
import io.github.lemcoder.util.execCapture
import io.github.lemcoder.util.marshalStub
import io.github.lemcoder.util.parseBridgeKinds
import io.github.lemcoder.util.stripCinterop
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import org.gradle.api.provider.Property
import java.io.File
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Generates the JNI `.c` stub and the runtime-free Kotlin bindings from a cinterop `.def`.
 *
 * The same def feeds the native targets' cinterop, so the two legs cannot drift: only `package` is
 * overridden here, since the JVM bindings live in their own package.
 */
abstract class GenerateJvmInteropTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val konanPath: Property<String>

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val defFile: RegularFileProperty

    @get:Input abstract val packageName: Property<String>

    /** Extra include roots; the def's own directory is always added. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    @get:Input abstract val hostTarget: Property<KonanTarget>
    @get:Input abstract val jniIncludeDirs: ListProperty<String>
    @get:Input @get:Optional abstract val additionalCompilerArgs: ListProperty<String>

    /** Emit the bridges as `internal`, keeping them out of the module's published API. */
    @get:Input abstract val internalBindings: Property<Boolean>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    /**
     * Directory holding the generated JNI `.c` stub — the handover point when another build system
     * compiles it. Derived from [outputDirectory] so the layout stays the plugin's to change.
     */
    @get:Internal
    val stubSourceDirectory: Provider<Directory> get() = outputDirectory.dir(C_DIR)

    /** The generated stub itself, named after the binding package. */
    @get:Internal
    val stubSourceFile: Provider<RegularFile>
        get() = outputDirectory.file(packageName.map { "$C_DIR/${JvmInteropSupport.stubBaseName(it)}.c" })

    /**
     * Base name of the JNI library the generated bindings will `System.loadLibrary`, derived from the
     * binding package. A build that links the stub itself has to produce exactly this name.
     */
    @get:Internal
    val stubLibraryBaseName: Provider<String> get() = packageName.map { JvmInteropSupport.stubBaseName(it) }

    /** Directory holding the generated Kotlin bindings. */
    @get:Internal
    val kotlinSourceDirectory: Provider<Directory> get() = outputDirectory.dir(KOTLIN_DIR)

    @TaskAction
    fun run() {
        val konanHome = File(konanPath.get())
        val embeddableJar = konanHome.resolve("konan/lib/kotlin-native-compiler-embeddable.jar")
        check(embeddableJar.isFile) { "Embeddable compiler jar not found: $embeddableJar" }
        val nativeLibDir = konanHome.resolve("konan/nativelib")

        val out = outputDirectory.get().asFile
        out.deleteRecursively(); out.mkdirs()

        val userDef = defFile.get().asFile
        val parsed = DefFile.parse(userDef)
        val pkg = packageName.orNull ?: parsed.packageName ?: error(
            "jvmInterops: neither packageName nor a `package =` line in ${userDef.name}."
        )

        // The generator wants a def of its own: same contents, our package.
        val effectiveDef = out.resolve("${name}.def")
        effectiveDef.writeText(parsed.render(pkg))

        val includes = (listOf(userDef.parentFile) + includeDirs.files + jniIncludeDirs.get().map(::File))
            .filter { it.exists() }
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
                "-def", effectiveDef.absolutePath,
                "-generated", out.resolve(KOTLIN_DIR).absolutePath,
                "-Xtemporary-files-dir", out.resolve(C_DIR).absolutePath,
                // The generator indexes headers once on the host; the bridges are platform-independent.
                "-target", hostTarget.get().name,
            )
            args(includes); args(extraOpts)
            environment("LIBCLANG_DISABLE_CRASH_RECOVERY", "1")
        }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()

        // Strip kotlinx.cinterop from the generated Kotlin so it ships on plain JVM / Android, and
        // marshal strings/primitive buffers on both sides of the bridge — the raw output passes them
        // as addresses, which only a Kotlin/Native caller can produce.
        val kotlinFiles = out.resolve(KOTLIN_DIR).walkTopDown().filter { it.extension == "kt" }.toList()
        val kinds = kotlinFiles.fold(emptyMap<Int, List<ParamKind>>()) { acc, kt ->
            acc + parseBridgeKinds(kt.readText())
        }
        val internal = internalBindings.getOrElse(true)
        kotlinFiles.forEach { kt -> kt.writeText(stripCinterop(kt.readText(), kinds, internal)) }
        out.resolve(C_DIR).walkTopDown().filter { it.extension == "c" }
            .forEach { c -> c.writeText(marshalStub(c.readText(), kinds)) }
    }

    private companion object {
        const val C_DIR = "c"
        const val KOTLIN_DIR = "kotlin"
    }
}
