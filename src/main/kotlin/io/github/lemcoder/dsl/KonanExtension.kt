package io.github.lemcoder.dsl

import io.github.lemcoder.abiDir
import io.github.lemcoder.androidKonanTargets
import io.github.lemcoder.isAndroid
import io.github.lemcoder.jni.JniStubBuild
import io.github.lemcoder.library.LibraryProvider
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import io.github.lemcoder.library.NativeArtifacts
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

/**
 * The `konan { }` block: the native libraries a project builds, and the bindings that reach them from
 * Kotlin.
 *
 * ```kotlin
 * konan {
 *     val mymath = libraries.create("mymath") {
 *         targets(KonanTarget.MACOS_ARM64, KonanTarget.ANDROID_ARM64)
 *     }
 *     bindings.create("mymath") {
 *         library(mymath)
 *         packageName.set("example")
 *     }
 * }
 * ```
 *
 * A binding is consumed wherever the project's Kotlin targets need it: Kotlin/Native targets through
 * KGP's own cinterop, JVM targets and Android through generated JNI bindings, and — when asked for with
 * [BindingSpec.ffm] — the JVM through jextract's FFM bindings.
 */
abstract class KonanExtension @Inject constructor(objects: ObjectFactory) {
    /**
     * The Kotlin/Native distribution that compiles libraries and links JNI stubs. Defaults to
     * `$KONAN_HOME`, else the newest `~/.konan/kotlin-native-prebuilt-*`, found when a task runs.
     */
    abstract val konanHome: DirectoryProperty

    /** Native libraries this project builds from source, one archive per target. */
    val libraries: NamedDomainObjectContainer<NativeLibrarySpec> =
        objects.domainObjectContainer(NativeLibrarySpec::class.java)

    /** C APIs to reach from Kotlin, each described by a cinterop `.def`. */
    val bindings: NamedDomainObjectContainer<BindingSpec> =
        objects.domainObjectContainer(BindingSpec::class.java)

    fun libraries(action: Action<NamedDomainObjectContainer<NativeLibrarySpec>>) = action.execute(libraries)

    fun bindings(action: Action<NamedDomainObjectContainer<BindingSpec>>) = action.execute(bindings)
}

/**
 * A C/C++ library built into a static archive per target, `<outputDirectory>/<target>/lib<name>.a`: by
 * Kotlin/Native's clang from [sourceDirectory], or by the project's own build ([zig], [external]).
 */
abstract class NativeLibrarySpec @Inject constructor(private val name: String, objects: ObjectFactory) : Named {
    override fun getName(): String = name

    /** Targets to build for. Required. */
    abstract val targets: ListProperty<KonanTarget>

    /** The C/C++ sources. Defaults to `native/`. */
    abstract val sourceDirectory: DirectoryProperty

    /** Include roots beyond [sourceDirectory], which always is one. */
    abstract val includeDirs: ConfigurableFileCollection

    /** Extra clang options, appended to `-fno-sanitize=undefined -fPIC`. */
    abstract val compilerOptions: ListProperty<String>

    /** Root of the per-target archives. Defaults to `build/konan/libraries/<name>`. */
    abstract val outputDirectory: DirectoryProperty

    fun targets(vararg targets: KonanTarget) = this.targets.set(targets.toList())

    /** All four Android ABIs. */
    fun androidTargets() = targets.set(androidKonanTargets())

    /** The archive built for [target]. */
    fun archive(target: KonanTarget): Provider<RegularFile> = outputDirectory.file(NativeArtifacts.archivePath(name, target))

    /** The directory holding [target]'s archive. */
    fun archiveDirectory(target: KonanTarget): Provider<Directory> = outputDirectory.dir(target.name)

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

    /** The provider blocks this library's script called; at most one may be. */
    internal val selectedProviders = mutableSetOf<LibraryProvider>()

    init {
        zig.projectDirectory.convention(sourceDirectory)
        zig.artifactName.convention(name)
    }
}

/**
 * `zig build` for a library, once per target with `-Dtarget` set to the Zig triple that matches
 * Kotlin/Native's sysroot. The `build.zig` must install a static library ([artifactName]).
 */
abstract class ZigSettings {
    init {
        executable.convention(Executable.OnPath("zig"))
        optimize.convention(ZigOptimize.RELEASE_FAST)
    }

    /** The directory holding `build.zig`. Defaults to the library's `sourceDirectory`. */
    abstract val projectDirectory: DirectoryProperty

    /** The `zig` program. Defaults to `zig` on the PATH or in the usual install locations. */
    abstract val executable: Property<Executable>

    /**
     * `-Doptimize`. Defaults to [ZigOptimize.RELEASE_FAST]: Zig's `DEBUG` and `RELEASE_SAFE` compile C with
     * UBSan traps, whose runtime the Kotlin/Native and JNI links do not have.
     */
    abstract val optimize: Property<ZigOptimize>

    /** Further `zig build` arguments, e.g. your own `-D` options. */
    abstract val arguments: ListProperty<String>

    /** The static library `build.zig` installs. Defaults to the library's name. */
    abstract val artifactName: Property<String>
}

/** Zig's optimisation modes, as `zig build -Doptimize` spells them. */
enum class ZigOptimize(val zigName: String) {
    DEBUG("Debug"),
    RELEASE_SAFE("ReleaseSafe"),
    RELEASE_FAST("ReleaseFast"),
    RELEASE_SMALL("ReleaseSmall"),
}

/**
 * A C API, described by a cinterop `.def`, and how each kind of consumer reaches it.
 */
abstract class BindingSpec @Inject constructor(private val name: String, objects: ObjectFactory) : Named {
    override fun getName(): String = name

    /** The `.def` to bind. Defaults to cinterop's own convention, `src/nativeInterop/cinterop/<name>.def`. */
    abstract val defFile: RegularFileProperty

    /**
     * The library the bindings call into, one of `konan.libraries`. The plugin then links its archive for
     * every consumer — the `.def` needs no `staticLibraries` — and builds it first.
     */
    abstract val library: Property<NativeLibrarySpec>

    /** Overrides the `.def`'s `package`. */
    abstract val packageName: Property<String>

    /** Include roots beyond the `.def`'s own directory. */
    abstract val includeDirs: ConfigurableFileCollection

    /** Extra clang options for reading the headers. */
    abstract val compilerOptions: ListProperty<String>

    fun library(library: NativeLibrarySpec) = this.library.set(library)

    /** JNI bindings for JVM targets and Android; generated whenever the project has such a target. */
    val jni: JniSpec = objects.newInstance(JniSpec::class.java)

    fun jni(action: Action<in JniSpec>) = action.execute(jni)

    /**
     * FFM bindings, generated by jextract, for JDK 22+. Off unless [ffm] is called; in a `kotlin("jvm")`
     * project they then replace the JNI bindings, which would define the same classes.
     */
    val ffm: FfmSpec = objects.newInstance(FfmSpec::class.java)

    /** Turns on FFM bindings. */
    fun ffm(action: Action<in FfmSpec>) {
        ffm.isEnabled = true
        action.execute(ffm)
    }

    /** Turns on FFM bindings with every default. */
    fun ffm() = ffm(Action {})
}

/** How the JNI bindings are generated and linked. */
abstract class JniSpec @Inject constructor(objects: ObjectFactory) {
    /**
     * Visibility of the generated bindings. They are an implementation detail — the idiomatic API is
     * written on top of them — so they are `internal` by default.
     */
    abstract val visibility: Property<BindingVisibility>

    /**
     * Targets to link the JNI library for. Defaults to the library's targets a JVM can load — the build
     * host and Android — or the host when there is no library.
     */
    abstract val targets: ListProperty<KonanTarget>

    /** An archive to link instead of the library's, e.g. one built elsewhere. */
    abstract val archive: RegularFileProperty

    /** A JDK home that ships `include/jni.h`. Defaults to a scan of installed JDKs. */
    abstract val jdkHome: DirectoryProperty

    /** Extra options for every target's link command. */
    abstract val linkerOptions: ListProperty<String>

    /** Extra link options for one target, after [linkerOptions]. */
    abstract val targetLinkerOptions: MapProperty<KonanTarget, List<String>>

    /** Where the linked library ends up, whoever linked it: put it on `java.library.path`, or package it. */
    abstract val libraryDirectory: DirectoryProperty

    fun targets(vararg targets: KonanTarget) = this.targets.set(targets.toList())

    /** Appends [options] to the link command of [target] only. */
    fun linkerOptionsFor(target: KonanTarget, vararg options: String) {
        val existing = targetLinkerOptions.getOrElse(emptyMap())[target].orEmpty()
        targetLinkerOptions.put(target, existing + options)
    }

    /** An external CMake build that compiles the stub; declaring it replaces the plugin's own link. */
    val cmake: CMakeSettings = objects.newInstance(CMakeSettings::class.java)

    fun cmake(action: Action<in CMakeSettings>) {
        selectedStubBuilds += JniStubBuild.External.CMake
        action.execute(cmake)
    }

    /** Any other build system that compiles the stub, as a list of commands; replaces the plugin's own link. */
    val external: ExternalBuildSettings = objects.newInstance(ExternalBuildSettings::class.java)

    fun external(action: Action<in ExternalBuildSettings>) {
        selectedStubBuilds += JniStubBuild.External.Generic
        action.execute(external)
    }

    /** The directory holding the generated `.c` stub — what an external build compiles. */
    val stubDirectory: Provider<Directory> get() = stubDirectoryProperty

    /** The library name the generated bindings load: an external build must produce exactly that. */
    val stubLibraryName: Provider<String> get() = stubLibraryNameProperty

    /** Where [target]'s JNI library must land: `jniLibs/<abi>/` for Android, a per-target directory otherwise. */
    fun libraryDirectoryFor(target: KonanTarget): Provider<Directory> =
        if (target.isAndroid) jniLibsRoot.dir(target.abiDir) else hostLibRoot.dir(target.name)

    internal val stubDirectoryProperty: DirectoryProperty = objects.directoryProperty()
    internal val stubLibraryNameProperty: Property<String> = objects.property(String::class.java)

    // Only per-ABI libraries may live under jniLibs: AGP reads each subdirectory name as an ABI, and a
    // host library sitting in the root fails its merge with "… is not an ABI".
    internal val jniLibsRoot: DirectoryProperty = objects.directoryProperty()
    internal val hostLibRoot: DirectoryProperty = objects.directoryProperty()

    /** The stub build blocks this binding's script called; at most one may be. */
    internal val selectedStubBuilds = mutableSetOf<JniStubBuild>()
}

/** How the FFM bindings are generated. The generated Java needs a JDK 22+ toolchain. */
abstract class FfmSpec {
    /** The jextract executable. Defaults to `jextract` on the PATH. */
    abstract val jextract: RegularFileProperty

    /**
     * Where the shared library the bindings load ends up, for the build host: put it on
     * `java.library.path`, together with `--enable-native-access`.
     */
    abstract val libraryDirectory: DirectoryProperty

    internal var isEnabled: Boolean = false
}

/** Whether generated bindings are visible outside the module that declares them. */
enum class BindingVisibility { INTERNAL, PUBLIC }
