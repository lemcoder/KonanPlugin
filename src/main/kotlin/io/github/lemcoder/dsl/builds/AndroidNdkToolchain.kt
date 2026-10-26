package io.github.lemcoder.dsl.builds

import io.github.lemcoder.dsl.config.ConfigValue
import io.github.lemcoder.model.Architecture
import io.github.lemcoder.model.HostOs
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.provider.Provider
import java.io.File

/**
 * An installed Android NDK's compilers for one Android target, which external builds get instead of
 * Kotlin/Native's when `konan.config.androidNdk` is set: the LLVM toolchain's per-API clang drivers,
 * `llvm-ar` and `llvm-ranlib`, and `build/cmake/android.toolchain.cmake` with `ANDROID_ABI` and
 * `ANDROID_PLATFORM`. Every file is checked when the build reads it, so a wrong NDK fails the build, not
 * configuration.
 */
internal object AndroidNdkToolchain {
    fun files(ndk: Provider<ConfigValue>, target: NativeTarget, apiLevel: Provider<Int>): ToolchainFiles {
        val home = ndk.map { it.require() }
        val windows = HostOs.current == HostOs.WINDOWS
        val bin = home.map { it.resolve("toolchains/llvm/prebuilt/${hostTag()}/bin") }
        fun tool(name: Provider<String>): Provider<File> = bin.zip(name) { directory, file -> directory.resolve(file) }
            .zip(home) { file, ndkHome -> file.also { check(it.isFile) { "konan.config.androidNdk $ndkHome has no ${it.relativeTo(ndkHome)}" } } }
        val compiler = apiLevel.map { "${compilerTriple(target)}$it" }
        val script = if (windows) ".cmd" else ""
        val exe = if (windows) ".exe" else ""
        return ToolchainFiles(
            cc = tool(compiler.map { "$it-clang$script" }),
            cxx = tool(compiler.map { "$it-clang++$script" }),
            ar = tool(apiLevel.map { "llvm-ar$exe" }),
            ranlib = tool(apiLevel.map { "llvm-ranlib$exe" }),
            cmakeToolchainFile = home.map { ndkHome ->
                ndkHome.resolve("build/cmake/android.toolchain.cmake")
                    .also { check(it.isFile) { "konan.config.androidNdk $ndkHome has no build/cmake/android.toolchain.cmake" } }
            },
            cmakeDefinitions = mapOf(
                "ANDROID_ABI" to apiLevel.map { checkNotNull(target.androidAbi) },
                "ANDROID_PLATFORM" to apiLevel.map { "android-$it" },
            ),
            androidNdk = home,
        )
    }

    /** The NDK's clang driver prefix: armv7a for 32-bit ARM, whose libraries are arm-linux-androideabi. */
    private fun compilerTriple(target: NativeTarget): String = when (target.architecture) {
        Architecture.ARM64 -> "aarch64-linux-android"
        Architecture.ARM32 -> "armv7a-linux-androideabi"
        Architecture.X64 -> "x86_64-linux-android"
        Architecture.X86 -> "i686-linux-android"
    }

    /** The NDK's host directory, named darwin-x86_64 even where its tools are universal binaries. */
    private fun hostTag(): String = when (HostOs.current) {
        HostOs.MACOS -> "darwin-x86_64"
        HostOs.LINUX -> "linux-x86_64"
        HostOs.WINDOWS -> "windows-x86_64"
    }
}
