package io.github.lemcoder.dsl

import io.github.lemcoder.model.Executable
import io.github.lemcoder.model.ExternalToolchain
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import java.io.File

/**
 * A Makefile that builds a library, run once per target as
 * `make -C <directory> -j<jobs> CC=… CXX=… AR=… <contract> <arguments> <buildTargets>`: Kotlin/Native's
 * compilers on the command line, so they win over the Makefile's own, and the contract — the output
 * directory and library name among it — as variables too.
 *
 * Every target builds in the same [directory], one after another or at once: keep intermediate files
 * apart per target (under the contract's output directory, or `KONAN_TARGET`), or a second target reuses
 * the first one's objects.
 */
abstract class MakeSettings {
    init {
        executable.convention(Executable.OnPath("make"))
        toolchain.convention(ExternalToolchain.AUTO)
        jobs.convention(Runtime.getRuntime().availableProcessors())
    }

    /** Where make runs (`-C`). Required. */
    abstract val directory: DirectoryProperty

    /** The Makefile, if not `Makefile` in [directory] (`-f`). */
    abstract val makefile: RegularFileProperty

    /** Make targets to build. Defaults to the Makefile's default goal. */
    abstract val buildTargets: ListProperty<String>

    /** Further arguments for every target, e.g. `VAR=value`. */
    abstract val arguments: ListProperty<String>

    /** Further arguments for one target, after [arguments]. */
    abstract val targetArguments: MapProperty<NativeTarget, List<String>>

    /** `-j`. Defaults to the number of processors. */
    abstract val jobs: Property<Int>

    /** The `make` program. Defaults to `make` on the PATH or in the usual install locations. */
    abstract val executable: Property<Executable>

    /** Whose compilers make gets: Kotlin/Native's when cross-compiling, by default. */
    abstract val toolchain: Property<ExternalToolchain>

    /** What the build reads. With none, it runs every time and make's own incrementality decides. */
    abstract val sources: ConfigurableFileCollection

    /** Where the build leaves each target's library, when not in the contract's output directory. */
    abstract val producedLibraries: MapProperty<NativeTarget, File>

    fun argumentsFor(target: NativeTarget, vararg arguments: String) {
        val existing = targetArguments.getOrElse(emptyMap())[target].orEmpty()
        targetArguments.put(target, existing + arguments)
    }

    /** The library the build leaves at [file] for [target]; the plugin copies and renames it into place. */
    fun producedLibrary(target: NativeTarget, file: File) = producedLibraries.put(target, file)
}
