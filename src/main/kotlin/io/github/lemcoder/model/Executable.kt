package io.github.lemcoder.model

import java.io.Serializable

/** A program an external build runs: a file, or a name looked up when the build runs. */
sealed class Executable : Serializable {
    /** This exact file. */
    data class File(val file: java.io.File) : Executable()

    /**
     * Found on the `PATH` when the build runs, then in the usual install locations: the Gradle daemon
     * does not inherit a login shell's `PATH`, so a Homebrew or MacPorts install is otherwise invisible.
     */
    data class OnPath(val name: String) : Executable()
}
