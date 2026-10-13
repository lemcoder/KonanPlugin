package io.github.lemcoder.dsl

import io.github.lemcoder.model.ExternalToolchain
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * An external CMake build that makes the library a binding loads — the JNI stub (`jni { cmake { } }`) or
 * the C library FFM loads (`ffm { cmake { } }`) — the way AGP's `externalNativeBuild` declares that CMake
 * owns a module's native code. The plugin then drives that build instead of linking anything itself: it
 * passes the contract as cache entries (`KONAN_JNI_STUB_DIR` and `KONAN_JNI_LIB_NAME`, or
 * `KONAN_FFM_LIB_NAME`, among others), sets `CMAKE_LIBRARY_OUTPUT_DIRECTORY` to where the library must
 * land, and orders it before whatever loads the library.
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
        toolchain.convention(ExternalToolchain.AUTO)
    }

    /**
     * Whose compilers CMake gets: Kotlin/Native's, as `-DCMAKE_TOOLCHAIN_FILE`, when cross-compiling, by
     * default. Not passed with a [preset], which names its own; a toolchain file in [arguments] wins.
     */
    abstract val toolchain: Property<ExternalToolchain>

    /** Builds for [target] — an Android ABI, say — with its own settings, instead of once for the host. */
    fun target(target: NativeTarget, action: Action<in CMakeTargetSettings>) {
        action.execute(targetSettings.getOrPut(target) { objects.newInstance(CMakeTargetSettings::class.java) })
    }

    /** The per-target builds declared; empty means a single build for the host. */
    internal val targetSettings = linkedMapOf<NativeTarget, CMakeTargetSettings>()

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
     * Directory holding the linked library, if the build does not leave it where
     * `CMAKE_LIBRARY_OUTPUT_DIRECTORY` says: for JNI, where consumers then look; for FFM, where the plugin
     * copies it from.
     */
    abstract val libraryDirectory: DirectoryProperty

    /** CMake targets to build (`--target`). Defaults to the build's default target. */
    abstract val buildTargets: ListProperty<String>

    /** Extra arguments for the configure step, e.g. your own `-D` switches. */
    abstract val arguments: ListProperty<String>

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
