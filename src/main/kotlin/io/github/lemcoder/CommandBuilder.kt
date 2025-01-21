package io.github.lemcoder

import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

class CommandBuilder(
    private val libType: LibraryType = LibraryType.SHARED,
    private val target: KonanTarget = KonanTarget.LINUX_X64,
    private val workingDir: File,
    private val libFileName: String,
) {

    private val sourcesDir: File = workingDir.resolve(SOURCES_DIR)
    private val headersDir: File = workingDir.resolve(HEADERS_DIR)
    private val compileDir: File = workingDir.resolve(COMPILE_DIR)

    private val fullLibFileName = "lib$libFileName.${getFileExtension(libType, target)}"

    fun build(): List<() -> String> {
        return when (libType) {
            LibraryType.STATIC -> listOf(::compileStaticLibraryCommand, ::buildStaticLibraryCommand)
            LibraryType.SHARED -> listOf(::buildSharedLibraryCommand)
        }
    }

    // Static

    private fun buildStaticLibraryCommand(): String {
        val objFilesPaths = compileDir
            .walk()
            .filter { it.extension == "o" }
            .joinToString("\n") { it.invariantSeparatorsPath }

        return "llvm llvm-ar -rv $fullLibFileName $objFilesPaths".trim()
    }

    private fun compileStaticLibraryCommand(): String {
        val sourceFilePaths = sourcesDir
            .walk()
            .filter { it.extension in listOf("cpp", "c") }
            .joinToString("\n") { it.invariantSeparatorsPath }

        val arguments = listOf(
            "--include-directory \"${headersDir.invariantSeparatorsPath}\"",
            "\"-std=c99\"",
            "\"-fno-sanitize=undefined\"",
            "-D" + "JPH_CROSS_PLATFORM_DETERMINISTIC",
            "-D" + "JPH_ENABLE_ASSERTS",
            "-c $sourceFilePaths"
        )

        return "clang clang $target ${arguments.joinToString(" ")}".trim()
    }

    // Shared

    private fun buildSharedLibraryCommand(): String {
        val sourceFilePaths = sourcesDir
            .walk()
            .filter { it.extension in listOf("cpp", "c") }
            .joinToString("\n") { it.invariantSeparatorsPath }

        val arguments = mutableListOf(
            "--include-directory \"${headersDir.invariantSeparatorsPath}\"",
            "\"-std=c99\"",
            "\"-fno-sanitize=undefined\"",
            "-D" + "JPH_CROSS_PLATFORM_DETERMINISTIC",
            "-D" + "JPH_ENABLE_ASSERTS",
            "${getSharedLibCommand()}",
            "-o $fullLibFileName",
            "-c $sourceFilePaths"
        )

        // Add -fPIC for Unix-based targets (Linux/macOS), but not for Windows
        if (target.supportsPositionIndependentCode()) {
            arguments.add("-fPIC")
        }

        return "clang clang $target ${arguments.joinToString(" ")}".trim()
    }

    private fun getSharedLibCommand(): String {
        return when (target) {
            // macOS and iOS use -dynamiclib
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
            KonanTarget.WATCHOS_X86 -> "-dynamiclib"
            // Default is -shared
            else                    -> "-shared"
        }
    }
}