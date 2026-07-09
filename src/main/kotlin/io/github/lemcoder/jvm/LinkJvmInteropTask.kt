package io.github.lemcoder.jvm

import io.github.lemcoder.util.execCapture
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
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
    @get:Input abstract val target: Property<String>
    @get:Input abstract val stubBaseName: Property<String>
    @get:InputFile abstract val stubCFile: RegularFileProperty
    @get:Input abstract val headerDir: Property<String>
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
        val outLib = outDir.resolve("lib${stubBaseName.get()}${JvmInteropSupport.sharedLibExt(tgt)}")

        val cmd = buildList {
            add(runKonan.absolutePath); add("clang"); add("clang"); add(tgt)
            add("-shared")
            add("-I${headerDir.get()}")
            if (tgt.startsWith("android")) {
                // jni.h comes from the NDK sysroot; supply compiler-rt and skip the (absent) unwinder.
                ndkResourceDir.orNull?.takeIf { it.isNotEmpty() }?.let { add("-resource-dir=$it") }
                add("--unwindlib=none")
            } else {
                jniIncludeDirs.getOrElse(emptyList()).forEach { add("-I$it") }
            }
            add(stubCFile.get().asFile.absolutePath)
            add(staticLibrary.get().asFile.absolutePath)
            add("-o"); add(outLib.absolutePath)
            addAll(additionalLinkerArgs.getOrElse(emptyList()))
        }

        val result = exec.execCapture { commandLine(cmd) }
        logger.lifecycle(result.output)
        result.assertNormalExitValue()
        logger.lifecycle("JNI stub library: $outLib")
    }
}