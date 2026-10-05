package io.github.lemcoder.dsl

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

/**
 * An external CMake build that compiles the generated JNI stub, the way AGP's `externalNativeBuild`
 * declares that CMake owns a module's native code. The plugin then stops after generating and drives
 * that build instead of linking anything itself: it passes the stub's location and the library name the
 * bindings expect (`KONAN_JNI_STUB_DIR`, `KONAN_JNI_LIB_NAME`), points `JAVA_HOME` at a JDK that ships
 * `jni.h`, and orders generation before configuration before build.
 *
 * This is the right shape whenever the native library is not the plugin's to build — a vendored CMake
 * project, say — because linking an archive from a foreign toolchain with konan's linker is what
 * produces mismatched C++ runtimes and missing compiler-rt.
 *
 * Either name a [preset] or let the plugin configure [path] into [buildDirectory] directly; everything
 * else has a default.
 *
 * Declaring [target]s builds once per target instead of once for the host, which is what Android needs:
 * the libraries land in `jniLibs/<abi>/` and are packaged into the AAR.
 */
abstract class CMakeSettings @Inject constructor(private val objects: ObjectFactory) {

    init {
        executable.convention(Executable.OnPath("cmake"))
    }

    /** Builds for [target] — an Android ABI, say — with its own settings, instead of once for the host. */
    fun target(target: KonanTarget, action: Action<in CMakeTargetSettings>) {
        action.execute(targetSettings.getOrPut(target) { objects.newInstance(CMakeTargetSettings::class.java) })
    }

    /** The per-target builds declared; empty means a single build for the host. */
    internal val targetSettings = linkedMapOf<KonanTarget, CMakeTargetSettings>()

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
     * Directory holding the linked JNI library, if the build does not leave it where
     * `CMAKE_LIBRARY_OUTPUT_DIRECTORY` says.
     */
    abstract val libraryDirectory: DirectoryProperty

    /** CMake targets to build (`--target`). Defaults to the build's default target. */
    abstract val buildTargets: ListProperty<String>

    /** Extra arguments for the configure step, e.g. your own `-D` switches. */
    abstract val arguments: ListProperty<String>

    /** The `cmake` program. Defaults to `cmake` on the PATH or in the usual install locations. */
    abstract val executable: Property<Executable>
}

/** One target's build: everything not set here comes from the enclosing [CMakeSettings]. */
abstract class CMakeTargetSettings {
    /** Configure preset for this target. */
    abstract val preset: Property<String>

    /** Build directory for this target; defaults the same way as the enclosing block's. */
    abstract val buildDirectory: DirectoryProperty

    /** Extra configure arguments for this target only. */
    abstract val arguments: ListProperty<String>
}
