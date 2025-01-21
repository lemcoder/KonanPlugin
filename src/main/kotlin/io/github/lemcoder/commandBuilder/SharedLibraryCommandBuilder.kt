package io.github.lemcoder.commandBuilder

import io.github.lemcoder.LibraryType
import io.github.lemcoder.getFileExtension
import io.github.lemcoder.supportsPositionIndependentCode
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

internal class SharedLibraryCommandBuilder(
    private val target: KonanTarget,
    private val workingDir: File,
    private val libFileName: String,
) : CommandBuilder(workingDir) {

    private val fullLibFileName = "lib$libFileName.${getFileExtension(LibraryType.SHARED, target)}"

    override fun build(): List<Command> = listOf(::buildSharedLibraryCommand)

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