package io.github.lemcoder

import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.ByteArrayOutputStream

const val SOURCES_DIR = "sources"
const val HEADERS_DIR = "headers"
const val COMPILE_DIR = "compile"

fun ExecOperations.execCapture(
    configure: ExecSpec.() -> Unit,
): ExecCaptureResult {

    val (result, output) = ByteArrayOutputStream().use { os ->
        exec {
            isIgnoreExitValue = true
            standardOutput = os
            errorOutput = os
            configure()
        } to os.toString()
    }

    return if (result.exitValue != 0) {
        ExecCaptureResult.Error(output, result)
    } else {
        ExecCaptureResult.Success(output, result)
    }
}


sealed class ExecCaptureResult(
    val output: String,
    private val result: ExecResult,
) : ExecResult by result {
    class Success(output: String, result: ExecResult) : ExecCaptureResult(output, result)
    class Error(output: String, result: ExecResult) : ExecCaptureResult(output, result)
}

fun getFileExtension(libType: LibraryType, target: KonanTarget): String {
    return when (libType) {
        LibraryType.STATIC -> "a"
        LibraryType.SHARED -> when (target) {
            // Shared library targets for Android
            KonanTarget.ANDROID_ARM32,
            KonanTarget.ANDROID_ARM64,
            KonanTarget.ANDROID_X64,
            KonanTarget.ANDROID_X86 -> "so"

            // Shared library targets for Linux
            KonanTarget.LINUX_ARM32_HFP,
            KonanTarget.LINUX_ARM64,
            KonanTarget.LINUX_MIPS32,
            KonanTarget.LINUX_MIPSEL32,
            KonanTarget.LINUX_X64   -> "so"

            // macOS and iOS targets
            KonanTarget.MACOS_ARM64,
            KonanTarget.MACOS_X64,
            KonanTarget.IOS_ARM32,
            KonanTarget.IOS_ARM64,
            KonanTarget.IOS_SIMULATOR_ARM64,
            KonanTarget.IOS_X64,
            KonanTarget.TVOS_ARM64,
            KonanTarget.TVOS_SIMULATOR_ARM64,
            KonanTarget.TVOS_X64,
            KonanTarget.WATCHOS_ARM32,
            KonanTarget.WATCHOS_ARM64,
            KonanTarget.WATCHOS_DEVICE_ARM64,
            KonanTarget.WATCHOS_SIMULATOR_ARM64,
            KonanTarget.WATCHOS_X64,
            KonanTarget.WATCHOS_X86 -> "dylib"

            // Windows targets
            KonanTarget.MINGW_X64,
            KonanTarget.MINGW_X86   -> "dll"

            // WebAssembly target
            KonanTarget.WASM32      -> "wasm"

            // Zephyr target
            is KonanTarget.ZEPHYR   -> "so"
        }
    }
}

fun KonanTarget.supportsPositionIndependentCode(): Boolean {
    return when (this) {
        KonanTarget.MINGW_X64,
        KonanTarget.MINGW_X86 -> false
        else                  -> true
    }
}