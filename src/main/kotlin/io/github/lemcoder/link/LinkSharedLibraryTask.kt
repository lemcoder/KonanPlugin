package io.github.lemcoder.link

import io.github.lemcoder.kotlin.KonanDistribution
import io.github.lemcoder.model.NativeTarget
import io.github.lemcoder.model.TargetFamily
import io.github.lemcoder.toolchain.Xcode
import io.github.lemcoder.util.execCapture
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
import javax.inject.Inject

/**
 * Links a target's static archive into a shared library with Kotlin/Native's clang: with [stubCFile], the
 * generated JNI stub compiled against it (JNI); without, the whole archive on its own, every symbol
 * exported (FFM).
 */
@DisableCachingByDefault(because = "Native link output depends on the local toolchain; cheap to rebuild")
internal abstract class LinkSharedLibraryTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    /** The Kotlin/Native distribution whose clang links; resolved when the task runs. */
    @get:Internal abstract val konanHome: DirectoryProperty
    @get:Input abstract val target: Property<NativeTarget>

    /** Base name of the library, e.g. `examplestubs` for `libexamplestubs.so`. */
    @get:Input abstract val libraryBaseName: Property<String>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.RELATIVE) abstract val stubCFile: RegularFileProperty

    /** Include roots for the stub compile. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection

    /** Further include roots, as paths: ones that must not be tracked as inputs, like the `.def`'s directory. */
    @get:Input @get:Optional abstract val includePaths: ListProperty<String>

    /** The JDK's `include` directories, for `jni.h`; Android gets it from the NDK sysroot instead. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jniIncludeDirs: ConfigurableFileCollection

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val staticLibrary: RegularFileProperty
    @get:Input @get:Optional abstract val additionalLinkerArgs: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val distribution = KonanDistribution.resolve(konanHome.orNull?.asFile)
        check(distribution.runKonan.isFile) { "run_konan not found: ${distribution.runKonan}" }
        val nativeTarget = target.get()

        val outDir = outputDirectory.get().asFile
        outDir.deleteRecursively(); outDir.mkdirs()
        val outLib = outDir.resolve(nativeTarget.sharedLibraryName(libraryBaseName.get()))
        val archive = staticLibrary.get().asFile.absolutePath
        val stub = stubCFile.orNull?.asFile

        val arguments = buildList {
            add("-shared")
            includeDirs.files.filter { it.exists() }.forEach { add("-I${it.absolutePath}") }
            includePaths.getOrElse(emptyList()).forEach { add("-I$it") }
            if (nativeTarget.isAndroid) {
                // jni.h comes from the NDK sysroot; supply compiler-rt and skip the (absent) unwinder.
                distribution.ndkResourceDirectory()?.let { add("-resource-dir=${it.absolutePath}") }
                add("--unwindlib=none")
            } else {
                jniIncludeDirs.files.forEach { add("-I${it.absolutePath}") }
            }
            if (stub != null) {
                add(stub.absolutePath)
                add(archive)
            } else if (nativeTarget.family.isApple) {
                add("-Wl,-force_load,$archive")
            } else {
                add("-Wl,--whole-archive"); add(archive); add("-Wl,--no-whole-archive")
            }
            if (nativeTarget.family == TargetFamily.OSX) {
                // Konan's LLVM has no host compiler-rt; Apple-framework code needs its builtins.
                Xcode.macosCompilerRt(exec)?.let { add(it.absolutePath) }
            }
            add("-o"); add(outLib.absolutePath)
            // A C++ runtime, extra -L, frameworks: all caller-supplied. Adding konan's own NDK lib
            // dir here would pull its static libc in ahead of the sysroot's shared one, which fails
            // on x86_64 with "relocation R_X86_64_PC32 ... recompile with -fPIC".
            addAll(additionalLinkerArgs.getOrElse(emptyList()))
        }

        val result = exec.execCapture { commandLine(distribution.clang(nativeTarget, arguments)) }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
        logger.lifecycle("Shared library: $outLib")
    }
}
