package io.github.lemcoder.toolchain

import io.github.lemcoder.util.execCapture
import org.gradle.process.ExecOperations
import java.io.File

/** What the plugin borrows from Xcode on a macOS host. */
internal object Xcode {
    /**
     * Xcode's compiler-rt builtins for macOS. Kotlin/Native's `essentials` LLVM ships none for the host,
     * so a library that pulls in Apple-framework code (e.g. ggml's Metal backend) is otherwise short
     * `___isPlatformVersionAtLeast`.
     */
    fun macosCompilerRt(exec: ExecOperations): File? {
        val roots = listOfNotNull(
            developerDirectory(exec)?.resolve("Toolchains/XcodeDefault.xctoolchain/usr/lib/clang"),
            File("/Library/Developer/CommandLineTools/usr/lib/clang"),
        )
        return roots.filter { it.isDirectory }
            .flatMap { it.listFiles()?.sortedByDescending { version -> version.name }.orEmpty() }
            .map { it.resolve("lib/darwin/libclang_rt.osx.a") }
            .firstOrNull { it.isFile }
    }

    /** `xcode-select -p`, or null without Xcode — then only the Command Line Tools are tried. */
    private fun developerDirectory(exec: ExecOperations): File? = runCatching {
        exec.execCapture { commandLine("xcode-select", "-p") }
            .takeIf { it.exitValue == 0 }
            ?.let { File(it.output.trim()) }
            ?.takeIf { it.isDirectory }
    }.getOrNull()
}
