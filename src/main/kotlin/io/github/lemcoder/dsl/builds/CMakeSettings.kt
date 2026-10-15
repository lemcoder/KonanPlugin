package io.github.lemcoder.dsl.builds

import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Action
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * A CMake build that makes a library — a library's archive (`libraries { cmake { } }`), a binding's JNI
 * library (`jni { cmake { } }`) or the library FFM loads (`ffm { cmake { } }`) — the way AGP's
 * `externalNativeBuild` declares that CMake owns a module's native code. The plugin drives that build
 * instead of compiling or linking anything itself: it passes the contract as cache entries
 * (`KONAN_LIB_NAME`, `KONAN_OUTPUT_DIR`, `KONAN_TARGET`, `KONAN_ABI`, and the owner's own), sets
 * `CMAKE_LIBRARY_OUTPUT_DIRECTORY` and `CMAKE_ARCHIVE_OUTPUT_DIRECTORY` to where the library must land,
 * and orders it before whatever uses the library.
 *
 * This is the right shape whenever the native library is not the plugin's to build — a vendored CMake
 * project, say — because linking an archive from a foreign toolchain with konan's linker is what
 * produces mismatched C++ runtimes and missing compiler-rt.
 *
 * Either name a [preset] or let the plugin configure [path] into [buildDirectory] directly; everything
 * else has a default.
 *
 * [target] blocks give a target its own settings. For a binding's library they also choose the targets
 * when `targets` is not set: the libraries for Android ABIs land in `jniLibs/<abi>/` and are packaged.
 */
abstract class CMakeSettings @Inject constructor(private val objects: ObjectFactory) : ExternalBuildSettings {

    init {
        toolchain.convention(ExternalToolchain.AUTO)
    }

    /**
     * Whose compilers CMake gets: Kotlin/Native's, as `-DCMAKE_TOOLCHAIN_FILE`, when cross-compiling, by
     * default. Not passed with a [preset], which names its own; a toolchain file in [arguments] wins.
     */
    abstract val toolchain: Property<ExternalToolchain>

    /** Builds for [target] — an Android ABI, say — with its own settings. */
    fun target(target: NativeTarget, action: Action<in CMakeTargetSettings>) {
        action.execute(targetSettings.getOrPut(target) { objects.newInstance(CMakeTargetSettings::class.java) })
    }

    /** The per-target settings declared. */
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
     * Where the build leaves its library, if not where `CMAKE_*_OUTPUT_DIRECTORY` says: the plugin copies
     * it from there into place.
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
