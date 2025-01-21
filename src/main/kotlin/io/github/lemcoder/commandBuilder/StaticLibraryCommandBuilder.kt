package io.github.lemcoder.commandBuilder

import io.github.lemcoder.LibraryType
import io.github.lemcoder.getFileExtension
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File

internal class StaticLibraryCommandBuilder(
    private val target: KonanTarget,
    private val workingDir: File,
    private val libFileName: String,
) : CommandBuilder(workingDir) {

    private val fullLibFileName = "lib$libFileName.${getFileExtension(LibraryType.STATIC, target)}"

    override fun build(): List<Command> = listOf(::compileStaticLibraryCommand, ::linkStaticLibraryCommand)

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

    private fun linkStaticLibraryCommand(): String {
        val objFilesPaths = compileDir
            .walk()
            .filter { it.extension == "o" }
            .joinToString("\n") { it.invariantSeparatorsPath }

        return "llvm llvm-ar -rv $fullLibFileName $objFilesPaths".trim()
    }
}