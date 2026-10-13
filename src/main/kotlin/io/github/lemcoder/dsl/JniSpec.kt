package io.github.lemcoder.dsl

import io.github.lemcoder.jni.JniStubBuild
import io.github.lemcoder.model.BindingVisibility
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Action
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

/** How the JNI bindings are generated and linked. */
abstract class JniSpec @Inject constructor(objects: ObjectFactory) {
    init {
        visibility.convention(BindingVisibility.INTERNAL)
        bundleInJar.convention(true)
    }

    /**
     * Whether a JVM project's jar carries the JNI library of each JVM target, under `konan/natives/`: the
     * bindings then load it from there when `java.library.path` does not have it. On by default.
     */
    abstract val bundleInJar: Property<Boolean>

    /**
     * Visibility of the generated bindings. They are an implementation detail — the idiomatic API is
     * written on top of them — so they are `internal` by default.
     */
    abstract val visibility: Property<BindingVisibility>

    /**
     * Targets to link the JNI library for. Defaults to the library's targets a JVM can load — the build
     * host and Android — or the host when there is no library.
     */
    abstract val targets: ListProperty<NativeTarget>

    /** An archive to link instead of the library's, e.g. one built elsewhere. */
    abstract val archive: RegularFileProperty

    /** Extra options for every target's link command. */
    abstract val linkerOptions: ListProperty<String>

    /** Extra link options for one target, after [linkerOptions]. */
    abstract val targetLinkerOptions: MapProperty<NativeTarget, List<String>>

    /** Where the linked library ends up, whoever linked it: put it on `java.library.path`, or package it. */
    abstract val libraryDirectory: DirectoryProperty

    fun targets(vararg targets: NativeTarget) = this.targets.set(targets.toList())

    /** Appends [options] to the link command of [target] only. */
    fun linkerOptionsFor(target: NativeTarget, vararg options: String) {
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

    /** The project's Makefile compiles the stub, once per target; replaces the plugin's own link. */
    val make: MakeSettings = objects.newInstance(MakeSettings::class.java)

    fun make(action: Action<in MakeSettings>) {
        selectedStubBuilds += JniStubBuild.External.Make
        action.execute(make)
    }

    /** The directory holding the generated `.c` stub — what an external build compiles. */
    val stubDirectory: Provider<Directory> get() = stubDirectoryProperty

    /** The library name the generated bindings load: an external build must produce exactly that. */
    val stubLibraryName: Provider<String> get() = stubLibraryNameProperty

    /** Where [target]'s JNI library must land: `jniLibs/<abi>/` for Android, a per-target directory otherwise. */
    fun libraryDirectoryFor(target: NativeTarget): Provider<Directory> =
        (if (target.isAndroid) jniLibsRoot else hostLibRoot).dir(target.libraryDirectoryName)

    internal val stubDirectoryProperty: DirectoryProperty = objects.directoryProperty()
    internal val stubLibraryNameProperty: Property<String> = objects.property(String::class.java)

    // Only per-ABI libraries may live under jniLibs: AGP reads each subdirectory name as an ABI, and a
    // host library sitting in the root fails its merge with "… is not an ABI".
    internal val jniLibsRoot: DirectoryProperty = objects.directoryProperty()
    internal val hostLibRoot: DirectoryProperty = objects.directoryProperty()

    /** The stub build blocks this binding's script called; at most one may be. */
    internal val selectedStubBuilds = mutableSetOf<JniStubBuild>()
}
