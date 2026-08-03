package io.github.lemcoder.interop

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Declares that another build system compiles and links the generated JNI stub, the way AGP's
 * `externalNativeBuild` declares that CMake owns a module's native code.
 *
 * The plugin then stops after generating, and drives that build instead of linking anything itself:
 * it passes the stub's location and the library name the bindings expect, points `JAVA_HOME` at a JDK
 * that ships `jni.h`, and orders generation before configuration before build.
 *
 * This is the right shape whenever the native library is not the plugin's to build — a vendored CMake
 * project, say — because linking an archive from a foreign toolchain with konan's linker is what
 * produces mismatched C++ runtimes and missing compiler-rt.
 */
abstract class ExternalNativeBuildSettings @Inject constructor(objects: ObjectFactory) {

    /** CMake configuration; declaring it is what switches the interop to external-build mode. */
    val cmake: CMakeSettings = objects.newInstance(CMakeSettings::class.java)

    internal var isConfigured: Boolean = false

    /** Configures the CMake build that compiles the generated stub. */
    fun cmake(action: Action<in CMakeSettings>) {
        isConfigured = true
        action.execute(cmake)
    }
}

/**
 * How to invoke CMake. Either name a [preset] or let the plugin configure [path] into [buildDirectory]
 * directly; everything else has a default.
 */
abstract class CMakeSettings {

    /** The `CMakeLists.txt` to configure. Required unless [preset] is set. */
    abstract val path: RegularFileProperty

    /** A configure preset from the project's `CMakePresets.json`. */
    abstract val preset: Property<String>

    /**
     * Where CMake builds. Defaults to `<CMakeLists dir>/build/<preset>` with a preset — the layout
     * `binaryDir` conventionally uses — and to a directory under the Gradle build dir without one.
     */
    abstract val buildDirectory: DirectoryProperty

    /**
     * Directory holding the linked JNI library, if the build does not leave it in [buildDirectory]
     * (`CMAKE_LIBRARY_OUTPUT_DIRECTORY`).
     */
    abstract val libraryDirectory: DirectoryProperty

    /** Targets to build. Defaults to whatever the build's default target is. */
    abstract val targets: ListProperty<String>

    /** Extra arguments for the configure step, e.g. your own `-D` switches. */
    abstract val arguments: ListProperty<String>

    /** The `cmake` binary. Defaults to `$CMAKE`, else a scan of the usual locations, else `cmake`. */
    abstract val executable: Property<String>
}
