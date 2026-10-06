package io.github.lemcoder.dsl

import io.github.lemcoder.ffm.FfmLibraryBuild
import io.github.lemcoder.model.Executable
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Action
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import javax.inject.Inject

/**
 * How the FFM bindings are generated, and how the shared library they load comes to exist: linked by the
 * plugin from the binding's library (the default), built by the project's own build ([cmake],
 * [external]), or built elsewhere ([prebuilt]). On a JVM the generated Java needs a JDK 22+ toolchain.
 */
abstract class FfmSpec @Inject constructor(objects: ObjectFactory) {
    init {
        jextract.convention(Executable.OnPath("jextract"))
        panamaPortVersion.convention("v0.1.5")
    }

    /** The jextract program. Defaults to `jextract` on the PATH or in the usual install locations. */
    abstract val jextract: Property<Executable>

    /**
     * The version of PanamaPort (`io.github.vova7878.panama:Core`), the FFM backport an Android module's
     * bindings use; the plugin adds it as an `implementation` dependency. It needs `minSdk` 26.
     */
    abstract val panamaPortVersion: Property<String>

    /**
     * The library the bindings load: `System.loadLibrary(libraryName)`, so `lib<libraryName>.so`. Defaults
     * to the binding's library's name, else the binding's. Set it to a prebuilt library's own name so that
     * libraries depending on it by that name still find it.
     */
    abstract val libraryName: Property<String>

    /**
     * Targets to provide the shared library for: the build host on a JVM, 64-bit ABIs in an Android
     * module. Defaults to the binding's library's targets among those, else all of those.
     */
    abstract val targets: ListProperty<NativeTarget>

    /** Extra options for the plugin's own link of the library. */
    abstract val linkerOptions: ListProperty<String>

    /**
     * Where the shared library ends up: for the build host, to put on `java.library.path` together with
     * `--enable-native-access`; in an Android module, the `jniLibs` root the plugin packages.
     */
    abstract val libraryDirectory: DirectoryProperty

    fun targets(vararg targets: NativeTarget) = this.targets.set(targets.toList())

    /** Builds the shared library with the project's own build system, as a list of commands per target. */
    val external: ExternalBuildSettings = objects.newInstance(ExternalBuildSettings::class.java)

    fun external(action: Action<in ExternalBuildSettings>) {
        selectedLibraryBuilds += FfmLibraryBuild.External.Generic
        action.execute(external)
    }

    /**
     * Builds the shared library with the project's CMakeLists: configured per target with the contract as
     * cache entries and `CMAKE_LIBRARY_OUTPUT_DIRECTORY` set to where the bindings load it, then built.
     */
    val cmake: CMakeSettings = objects.newInstance(CMakeSettings::class.java)

    fun cmake(action: Action<in CMakeSettings>) {
        selectedLibraryBuilds += FfmLibraryBuild.External.CMake
        action.execute(cmake)
    }

    /** Shared libraries built elsewhere, one per target. */
    val prebuilt: FfmPrebuiltSettings = objects.newInstance(FfmPrebuiltSettings::class.java)

    fun prebuilt(action: Action<in FfmPrebuiltSettings>) {
        selectedLibraryBuilds += FfmLibraryBuild.Prebuilt
        action.execute(prebuilt)
    }

    /** Where [target]'s shared library must land: `jniLibs/<abi>/` on Android, a per-target directory otherwise. */
    fun libraryDirectoryFor(target: NativeTarget): Provider<Directory> =
        (if (target.isAndroid) jniLibsRoot else hostLibRoot).dir(target.libraryDirectoryName)

    internal val jniLibsRoot: DirectoryProperty = objects.directoryProperty()
    internal val hostLibRoot: DirectoryProperty = objects.directoryProperty()

    /** The library build blocks this binding's script called; at most one may be. */
    internal val selectedLibraryBuilds = mutableSetOf<FfmLibraryBuild>()

    internal var isEnabled: Boolean = false
}

/** Shared libraries the FFM bindings load, built elsewhere — a vendor's `.so`, say. */
abstract class FfmPrebuiltSettings {
    /** The shared library for each target. */
    abstract val sharedLibraries: MapProperty<NativeTarget, File>

    /** The shared library built for [target]; placed as `lib<libraryName>` with the target's suffix. */
    fun sharedLibrary(target: NativeTarget, file: File) = sharedLibraries.put(target, file)
}
