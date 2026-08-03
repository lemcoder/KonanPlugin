package io.github.lemcoder.jvm

import io.github.lemcoder.KonanTarget
import io.github.lemcoder.util.execCapture
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Compiles the generated `.c` and statically links the target's `.a` into a shared JNI library. */
abstract class LinkJvmInteropTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val konanPath: Property<String>
    @get:Input abstract val target: Property<KonanTarget>
    @get:Input abstract val stubBaseName: Property<String>
    @get:InputFile abstract val stubCFile: RegularFileProperty
    /** Include roots for the stub compile: the def's directory plus whatever the interop declared. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val includeDirs: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val staticLibrary: RegularFileProperty
    @get:Input @get:Optional abstract val jniIncludeDirs: ListProperty<String>
    @get:Input @get:Optional abstract val ndkResourceDir: Property<String>
    @get:Input @get:Optional abstract val additionalLinkerArgs: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun run() {
        val konanHome = File(konanPath.get())
        val tgt = target.get()
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val runKonan = konanHome.resolve(if (isWindows) "bin/run_konan.bat" else "bin/run_konan")
        check(runKonan.isFile) { "run_konan not found: $runKonan" }

        val outDir = outputDirectory.get().asFile
        outDir.deleteRecursively(); outDir.mkdirs()
        val outLib = outDir.resolve("lib${stubBaseName.get()}${tgt.sharedLibExtension}")

        val cmd = buildList {
            add(runKonan.absolutePath); add("clang"); add("clang"); add(tgt.konanName)
            add("-shared")
            includeDirs.files.filter { it.exists() }.forEach { add("-I${it.absolutePath}") }
            if (tgt.isAndroid) {
                // jni.h comes from the NDK sysroot; supply compiler-rt and skip the (absent) unwinder.
                ndkResourceDir.orNull?.takeIf { it.isNotEmpty() }?.let { add("-resource-dir=$it") }
                add("--unwindlib=none")
            } else {
                jniIncludeDirs.getOrElse(emptyList()).forEach { add("-I$it") }
            }
            add(stubCFile.get().asFile.absolutePath)
            add(staticLibrary.get().asFile.absolutePath)
            if (tgt.konanName.startsWith("macos")) {
                // Konan's LLVM has no host compiler-rt; Apple-framework code needs its builtins.
                JvmInteropSupport.appleCompilerRt(xcodeDeveloperDir())?.let { add(it.absolutePath) }
            }
            add("-o"); add(outLib.absolutePath)
            // A C++ runtime, extra -L, frameworks: all caller-supplied. Adding konan's own NDK lib
            // dir here would pull its static libc in ahead of the sysroot's shared one, which fails
            // on x86_64 with "relocation R_X86_64_PC32 ... recompile with -fPIC".
            addAll(additionalLinkerArgs.getOrElse(emptyList()))
        }

        val result = exec.execCapture { commandLine(cmd) }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
        logger.lifecycle("JNI stub library: $outLib")
    }

    /** `xcode-select -p`, or null when there is no Xcode (then only the Command Line Tools are tried). */
    private fun xcodeDeveloperDir(): File? = runCatching {
        exec.execCapture { commandLine("xcode-select", "-p") }
            .takeIf { it.exitValue == 0 }
            ?.let { File(it.output.trim()) }
            ?.takeIf { it.isDirectory }
    }.getOrNull()
}