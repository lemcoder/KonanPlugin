package io.github.lemcoder.dsl.builds

import io.github.lemcoder.dsl.builds.BuildSettings
import io.github.lemcoder.dsl.builds.CMakeSettings
import io.github.lemcoder.dsl.builds.MakeSettings
import io.github.lemcoder.dsl.builds.StepsSettings
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory

/**
 * The build blocks of everything that makes a library — `konan.libraries`, a binding's `jni` and `ffm`:
 * [cmake], [make] and [external] here, the rest on the owners that take them. Calling one selects its
 * [BuildSettings]; at most one may be called.
 */
abstract class ExternalBuildBlocks(objects: ObjectFactory) {
    /**
     * The project's CMakeLists, configured once per target with `CMAKE_LIBRARY_OUTPUT_DIRECTORY` and
     * `CMAKE_ARCHIVE_OUTPUT_DIRECTORY` set to where the library must land, then built —
     * `add_library(${KONAN_LIB_NAME} …)` is all it needs — with Kotlin/Native's compilers as a toolchain
     * file when cross-compiling.
     */
    val cmake: CMakeSettings = objects.newInstance(CMakeSettings::class.java)

    fun cmake(action: Action<in CMakeSettings>) = select(cmake, action)

    /** The project's Makefile, once per target, with Kotlin/Native's compilers on make's command line when cross-compiling. */
    val make: MakeSettings = objects.newInstance(MakeSettings::class.java)

    fun make(action: Action<in MakeSettings>) = select(make, action)

    /**
     * Any other build system, as a list of commands run once per target. The build writes the library into
     * `KONAN_OUTPUT_DIR`, or names where it leaves it with `producedLibrary`.
     */
    val external: StepsSettings = objects.newInstance(StepsSettings::class.java)

    fun external(action: Action<in StepsSettings>) = select(external, action)

    /** The blocks this owner's script called; at most one may be. */
    internal val selectedBuilds = mutableSetOf<BuildSettings>()

    /** Selects [settings] and configures them. */
    internal fun <T : BuildSettings> select(settings: T, action: Action<in T>) {
        selectedBuilds += settings
        action.execute(settings)
    }
}
