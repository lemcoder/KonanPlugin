package io.github.lemcoder.toolchain

import io.github.lemcoder.model.Executable
import io.github.lemcoder.model.HostOs
import java.io.File

/** Where installs live that a daemon's `PATH` misses. */
private val FALLBACK_DIRECTORIES = listOf("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin", "/usr/bin")

private val WINDOWS_EXTENSIONS = listOf("", ".exe", ".cmd", ".bat")

/** The command line's first element for this executable. Call it when the build runs, not before. */
internal fun Executable.command(): String = when (this) {
    is Executable.File -> file.absolutePath
    // Unresolved, the bare name still runs if the process's own PATH has it, and fails naming it if not.
    is Executable.OnPath -> find(name)?.absolutePath ?: name
}

private fun find(name: String): File? {
    val directories = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotEmpty() } + FALLBACK_DIRECTORIES
    val extensions = if (HostOs.current == HostOs.WINDOWS) WINDOWS_EXTENSIONS else listOf("")
    return directories.asSequence()
        .flatMap { directory -> extensions.asSequence().map { File(directory, name + it) } }
        .firstOrNull { it.isFile && it.canExecute() }
}
