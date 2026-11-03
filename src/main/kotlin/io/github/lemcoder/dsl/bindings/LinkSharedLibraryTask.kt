package io.github.lemcoder.dsl.bindings

import io.github.lemcoder.android.AndroidNdk
import io.github.lemcoder.dsl.config.ConfigValue
import io.github.lemcoder.kotlin.UsesKonanDistribution
import io.github.lemcoder.kotlin.distribution
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.util.execCapture
import org.gradle.api.provider.Provider
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
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
import org.gradle.work.DisableCachingByDefault

/**
 * Links a target's static archive into a shared library — with Kotlin/Native's clang, and for Android with
 * the NDK's ([androidNdk], required then): with [stubCFile], the generated JNI stub compiled against it
 * (JNI) — or against [additionalLinkerArgs] alone, for a platform library; without, the whole archive on its
 * own, every symbol exported (FFM).
 */
@DisableCachingByDefault(because = "Native link output depends on the local toolchain; cheap to rebuild")
internal abstract class LinkSharedLibraryTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask(), UsesKonanDistribution {
    /** The Kotlin/Native distribution whose clang links; resolved when the task runs. */
    @get:Internal abstract override val konanHome: Property<ConfigValue>
    @get:Input abstract val target: Property<NativeTarget>

    /** Base name of the library, e.g. `examplestubs` for `libexamplestubs.so`. */
    @get:Input abstract val libraryBaseName: Property<String>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val stubCFile: RegularFileProperty

    /** Include roots for the stub compile. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    /** Further include roots, as paths: ones that must not be tracked as inputs, like the `.def`'s directory. */
    @get:Input @get:Optional abstract val includePaths: ListProperty<String>

    /** The directory holding the generator's `jni.h`; Android gets it from the NDK sysroot instead. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniIncludeDirs: ConfigurableFileCollection

    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val staticLibrary: RegularFileProperty
    @get:Input @get:Optional abstract val additionalLinkerArgs: ListProperty<String>

    /** For Android targets, the API level the NDK compiles and links for. */
    @get:Input @get:Optional abstract val androidApiLevel: Property<Int>

    /** The Android NDK that links Android targets; set when this is one. */
    @get:Internal abstract val androidNdk: Property<ConfigValue>

    /** Which NDK — path and revision — so another one relinks. */
    @get:Input @get:Optional
    val androidNdkIdentity: Provider<String> get() = androidNdk.map { AndroidNdk.identity(it) }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val nativeTarget = target.get()
        val outDir = outputDirectory.get().asFile
        outDir.deleteRecursively(); outDir.mkdirs()
        val outLib = outDir.resolve(nativeTarget.sharedLibraryName(libraryBaseName.get()))
        val command = if (nativeTarget.isAndroid) ndkLink(nativeTarget, outLib) else konanLink(nativeTarget, outLib)
        val result = exec.execCapture { commandLine(command) }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
        logger.lifecycle("Shared library: $outLib")
    }

    /** Every target but Android's: Kotlin/Native's clang compiles the stub and links in one call. */
    private fun konanLink(nativeTarget: NativeTarget, outLib: File): List<String> {
        val distribution = distribution()
        check(distribution.runKonan.isFile) { "run_konan not found: ${distribution.runKonan}" }
        distribution.ensureDependencies(nativeTarget, exec, temporaryDir.resolve("dependencies"))
        val arguments = buildList {
            add("-shared")
            addAll(includeArguments())
            jniIncludeDirs.files.forEach { add("-I${it.absolutePath}") }
            addAll(distribution.linkArguments(nativeTarget, exec))
            addAll(inputs(nativeTarget, stubCFile.orNull?.asFile?.absolutePath))
            add("-o"); add(outLib.absolutePath)
            // A C++ runtime, extra -L, frameworks: all caller-supplied.
            addAll(additionalLinkerArgs.getOrElse(emptyList()))
        }
        return distribution.clang(nativeTarget, arguments)
    }

    /**
     * Android: the NDK compiles the stub as C, then its C++ driver links — with `-static-libstdc++`, so
     * libc++, libc++abi and libunwind are inside the library, which Android gives apps none of — and
     * `--no-undefined`, so a symbol nothing provides fails here, not as "cannot locate symbol" on the device.
     * The NDK's sysroot has `jni.h`.
     */
    private fun ndkLink(nativeTarget: NativeTarget, outLib: File): List<String> {
        val ndk = AndroidNdk.of(androidNdk.get())
        val apiLevel = androidApiLevel.getOrElse(AndroidNdk.DEFAULT_API_LEVEL)
        val stub = stubCFile.orNull?.asFile?.let { source ->
            val objectFile = temporaryDir.resolve("${source.nameWithoutExtension}.o")
            val compile = listOf(ndk.clang(nativeTarget, apiLevel).absolutePath, "-c", "-fPIC") + includeArguments() +
                listOf(source.absolutePath, "-o", objectFile.absolutePath)
            val result = exec.execCapture { commandLine(compile) }
            logger.lifecycle(result.output)
            result.assertNormalExitValue()
            objectFile.absolutePath
        }
        return buildList {
            add(ndk.clang(nativeTarget, apiLevel, cxx = true).absolutePath)
            add("-shared"); add("-static-libstdc++"); add("-Wl,--no-undefined")
            addAll(inputs(nativeTarget, stub))
            add("-o"); add(outLib.absolutePath)
            addAll(additionalLinkerArgs.getOrElse(emptyList()))
        }
    }

    private fun includeArguments(): List<String> =
        includeDirs.files.filter { it.exists() }.map { "-I${it.absolutePath}" } + includePaths.getOrElse(emptyList()).map { "-I$it" }

    /** The stub (source or object) with the archive, or the whole archive on its own. */
    private fun inputs(nativeTarget: NativeTarget, stub: String?): List<String> {
        val archive = staticLibrary.orNull?.asFile?.absolutePath
        if (stub != null) return listOfNotNull(stub, archive)
        val whole = checkNotNull(archive) { "Nothing to link: neither an archive nor a JNI stub" }
        return if (nativeTarget.family.isApple) listOf("-Wl,-force_load,$whole")
        else listOf("-Wl,--whole-archive", whole, "-Wl,--no-whole-archive")
    }
}
