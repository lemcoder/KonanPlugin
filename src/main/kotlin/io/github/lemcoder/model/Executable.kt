package io.github.lemcoder.model

import io.github.lemcoder.dsl.config.ConfigValue
import java.io.Serializable

/** A program an external build step runs: a file, or a name the build's `PATH` resolves. */
sealed class Executable : Serializable {
    /** This exact file. */
    data class File(val file: java.io.File) : Executable()

    /**
     * Run by name, resolved by the `PATH` of the Gradle daemon — which does not inherit a login shell's, so
     * prefer [File] for anything outside the system directories.
     */
    data class OnPath(val name: String) : Executable()

    /** A program from `konan { config { } }`, checked when the step runs. */
    internal data class Configured(val value: ConfigValue) : Executable()
}
