package io.github.lemcoder.android

import io.github.lemcoder.dsl.config.ConfigValue
import io.github.lemcoder.model.Architecture
import io.github.lemcoder.model.HostOs
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.InvalidUserDataException
import java.io.File
import java.util.Properties

/**
 * An installed Android NDK, `konan.config.androidNdk`: what the plugin compiles and links every Android
 * library with — the default library build, the JNI library, the library FFM loads — and what external
 * builds get. Its LLVM toolchain's per-API clang drivers carry the NDK's own sysroot and C++ runtime, so
 * nothing of Kotlin/Native's (NDK r20) reaches an Android library.
 */
internal class AndroidNdk private constructor(val home: File) {
    private val bin = home.resolve("toolchains/llvm/prebuilt/${hostTag()}/bin")
    private val windows = HostOs.current == HostOs.WINDOWS

    /** `Pkg.Revision` from `source.properties`, e.g. `29.0.14206865`: what a task's up-to-date check tracks. */
    val revision: String by lazy {
        val properties = home.resolve("source.properties")
        if (!properties.isFile) "unknown" else Properties().apply { properties.inputStream().use(::load) }.getProperty("Pkg.Revision", "unknown")
    }

    /**
     * The clang driver for [target] at [apiLevel] — the C driver, or with [cxx] the C++ one, which links
     * libc++. An API level outside the NDK's range is clamped to it: below its lowest the NDK cannot build,
     * above its highest the newest it has is used.
     */
    fun clang(target: NativeTarget, apiLevel: Int, cxx: Boolean = false): File {
        val triple = compilerTriple(target)
        val levels = bin.listFiles().orEmpty().mapNotNull { Regex("^${Regex.escape(triple)}(\\d+)-clang$").find(it.nameWithoutExtension)?.groupValues?.get(1)?.toInt() }
        if (levels.isEmpty()) fail("has no clang for ${target.konanName} in ${bin.relativeTo(home)}")
        val level = apiLevel.coerceIn(levels.min(), levels.max())
        return tool("$triple$level-clang${if (cxx) "++" else ""}${if (windows) ".cmd" else ""}")
    }

    val llvmAr: File get() = tool("llvm-ar${if (windows) ".exe" else ""}")
    val llvmRanlib: File get() = tool("llvm-ranlib${if (windows) ".exe" else ""}")

    val cmakeToolchainFile: File
        get() = home.resolve("build/cmake/android.toolchain.cmake").also { if (!it.isFile) fail("has no build/cmake/android.toolchain.cmake") }

    private fun tool(name: String): File = bin.resolve(name).also { if (!it.isFile) fail("has no ${it.relativeTo(home)}") }

    private fun fail(problem: String): Nothing =
        throw InvalidUserDataException("konan.config.androidNdk $home $problem; is it an NDK (r23 or newer)?")

    companion object {
        /** The NDK [value] names, checked to exist: when it is unset, the error says which task needs it and how to set it. */
        fun of(value: ConfigValue): AndroidNdk = AndroidNdk(value.require())

        /** The NDK's path and revision, or null when unset: an Android task's input. */
        fun identity(value: ConfigValue): String? = value.raw?.let { raw -> "$raw@${AndroidNdk(File(raw)).revision}" }

        /** The NDK's clang driver prefix: armv7a for 32-bit ARM, whose libraries are arm-linux-androideabi. */
        fun compilerTriple(target: NativeTarget): String {
            require(target.isAndroid) { "${target.konanName} is not an Android target" }
            return when (target.architecture) {
                Architecture.ARM64 -> "aarch64-linux-android"
                Architecture.ARM32 -> "armv7a-linux-androideabi"
                Architecture.X64 -> "x86_64-linux-android"
                Architecture.X86 -> "i686-linux-android"
            }
        }

        /** The NDK's host directory, named darwin-x86_64 even where its tools are universal binaries. */
        private fun hostTag(): String = when (HostOs.current) {
            HostOs.MACOS -> "darwin-x86_64"
            HostOs.LINUX -> "linux-x86_64"
            HostOs.WINDOWS -> "windows-x86_64"
        }

        /** The API level when the module gives none (no `minSdk`): the lowest current NDKs build for. */
        const val DEFAULT_API_LEVEL = 21
    }
}
