package io.github.lemcoder.dsl

import io.github.lemcoder.library.LibraryProvider
import io.github.lemcoder.model.NativeArtifacts
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Provider
import javax.inject.Inject

/**
 * A C/C++ library built into a static archive per target, `<outputDirectory>/<target>/lib<name>.a`: by
 * Kotlin/Native's clang from [sourceDirectory], by the project's own build ([cmake], [zig], [cargo], [make], [external]), or built
 * elsewhere ([prebuilt]).
 */
abstract class NativeLibrarySpec @Inject constructor(private val name: String, objects: ObjectFactory) : Named {
    override fun getName(): String = name

    /** Targets to build for. Required, unless [prebuilt] declares them. */
    abstract val targets: ListProperty<NativeTarget>

    /** The C/C++ sources. Defaults to `native/`. */
    abstract val sourceDirectory: DirectoryProperty

    /** Include roots beyond [sourceDirectory], which always is one. */
    abstract val includeDirs: ConfigurableFileCollection

    /** Extra clang options, appended to `-fno-sanitize=undefined -fPIC`. */
    abstract val compilerOptions: ListProperty<String>

    /** Root of the per-target archives. Defaults to `build/konan/libraries/<name>`. */
    abstract val outputDirectory: DirectoryProperty

    fun targets(vararg targets: NativeTarget) = this.targets.set(targets.toList())

    /** All four Android ABIs. */
    fun androidTargets() = targets.set(NativeTarget.android)

    /** The archive built for [target]. */
    fun archive(target: NativeTarget): Provider<RegularFile> = outputDirectory.file(NativeArtifacts.archivePath(name, target))

    /** The directory holding [target]'s archive. */
    fun archiveDirectory(target: NativeTarget): Provider<Directory> = outputDirectory.dir(target.konanName)

    /**
     * Builds the library with the project's own `build.zig` instead of compiling [sourceDirectory] with
     * Kotlin/Native's clang. The archives land in the same place, so bindings do not change.
     */
    val zig: ZigSettings = objects.newInstance(ZigSettings::class.java)

    fun zig(action: Action<in ZigSettings>) {
        selectedProviders += LibraryProvider.External.Zig
        action.execute(zig)
    }

    /**
     * Builds the library with any other build system, as a list of commands run once per target. Each run
     * gets `KONAN_LIBRARY_NAME`, `KONAN_LIBRARY_OUTPUT_DIR`, `KONAN_LIBRARY_SOURCE_DIR` and `KONAN_TARGET`;
     * the build writes `lib<name>.a` into the output directory, or names where it leaves its archive with
     * `producedLibrary`.
     */
    val external: ExternalBuildSettings = objects.newInstance(ExternalBuildSettings::class.java)

    fun external(action: Action<in ExternalBuildSettings>) {
        selectedProviders += LibraryProvider.External.Generic
        action.execute(external)
    }

    /**
     * Builds the library with the project's Makefile, once per target, with Kotlin/Native's compilers on
     * make's command line when cross-compiling; the archive lands in `KONAN_LIBRARY_OUTPUT_DIR` as
     * `lib<name>.a`, or where `producedLibrary` says.
     */
    val make: MakeSettings = objects.newInstance(MakeSettings::class.java)

    fun make(action: Action<in MakeSettings>) {
        selectedProviders += LibraryProvider.External.Make
        action.execute(make)
    }

    /**
     * Builds the library with the project's CMakeLists, once per target: the contract as cache entries,
     * `CMAKE_ARCHIVE_OUTPUT_DIRECTORY` set to where the archive must land, and Kotlin/Native's compilers
     * as a toolchain file when cross-compiling — `add_library(${KONAN_LIBRARY_NAME} STATIC …)` is all the
     * CMakeLists needs.
     */
    val cmake: CMakeSettings = objects.newInstance(CMakeSettings::class.java)

    fun cmake(action: Action<in CMakeSettings>) {
        selectedProviders += LibraryProvider.External.CMake
        action.execute(cmake)
    }

    /** Builds the library from a Rust crate (`crate-type = ["staticlib"]`) with `cargo build`, once per target. */
    val cargo: CargoSettings = objects.newInstance(CargoSettings::class.java)

    fun cargo(action: Action<in CargoSettings>) {
        selectedProviders += LibraryProvider.External.Cargo
        action.execute(cargo)
    }

    /** Archives built elsewhere, one per target; [targets] default to the targets they are declared for. */
    val prebuilt: PrebuiltSettings = objects.newInstance(PrebuiltSettings::class.java)

    fun prebuilt(action: Action<in PrebuiltSettings>) {
        selectedProviders += LibraryProvider.Prebuilt
        action.execute(prebuilt)
    }

    /** The provider blocks this library's script called; at most one may be. */
    internal val selectedProviders = mutableSetOf<LibraryProvider>()

    init {
        zig.projectDirectory.convention(sourceDirectory)
        zig.artifactName.convention(name)
        cargo.crateName.convention(name)
        cmake.path.convention(sourceDirectory.file("CMakeLists.txt"))
        targets.convention(prebuilt.archives.keySet().map { it.toList() })
    }
}
