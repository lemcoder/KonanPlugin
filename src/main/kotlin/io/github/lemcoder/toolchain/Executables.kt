package io.github.lemcoder.toolchain

import io.github.lemcoder.model.Executable

/** The command line's first element for this executable. Call it when the build runs, not before. */
internal fun Executable.command(): String = when (this) {
    is Executable.File -> file.absolutePath
    is Executable.OnPath -> name
    is Executable.Configured -> value.require().absolutePath
}
