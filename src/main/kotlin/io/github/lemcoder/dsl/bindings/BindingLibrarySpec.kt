package io.github.lemcoder.dsl.bindings

import io.github.lemcoder.dsl.builds.ExternalBuildBlocks
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * The shared library a binding's JVM or Android bindings load, one per target — JNI's stub linked with the
 * C library, or for FFM the C library itself: which targets get one, how it is linked or built, where it
 * lands, and whether a JVM jar carries it. Linked by the plugin with Kotlin/Native's toolchain unless a
 * build block ([cmake], [make], [external]) is called.
 */
abstract class BindingLibrarySpec(objects: ObjectFactory) : ExternalBuildBlocks(objects) {
    init {
        bundleInJar.convention(true)
    }

    /**
     * Whether a JVM project's jar carries the library of each JVM target, under `konan/natives/`: the
     * bindings then load it from there when `java.library.path` does not have it. On by default.
     */
    abstract val bundleInJar: Property<Boolean>

    /**
     * Targets to provide the library for. Defaults to the targets of `cmake { target(…) { } }` blocks, else
     * the binding's library's targets the bindings can load, else what they can load.
     */
    abstract val targets: ListProperty<NativeTarget>

    /** Extra options for every target's link by the plugin. */
    abstract val linkerOptions: ListProperty<String>

    /** Extra link options for one target, after [linkerOptions]. */
    abstract val targetLinkerOptions: MapProperty<NativeTarget, List<String>>

    /**
     * Where the library ends up, whoever built it: for the build host, to put on `java.library.path`; in an
     * Android module, the `jniLibs` root the plugin packages.
     */
    abstract val libraryDirectory: DirectoryProperty

    /**
     * [targets] without repeats, which is what tasks are registered from: `targets(NativeTarget.host,
     * NativeTarget.LINUX_ARM64)` names one target twice on a Linux arm64 host.
     */
    internal val distinctTargets: Provider<List<NativeTarget>> get() = targets.map { it.distinct() }

    fun targets(vararg targets: NativeTarget) = this.targets.set(targets.toList())

    /** Appends [options] to the link command of [target] only. */
    fun linkerOptionsFor(target: NativeTarget, vararg options: String) {
        val existing = targetLinkerOptions.getOrElse(emptyMap())[target].orEmpty()
        targetLinkerOptions.put(target, existing + options)
    }

    /** Where [target]'s library must land: `jniLibs/<abi>/` for Android, a per-target directory otherwise. */
    fun libraryDirectoryFor(target: NativeTarget): Provider<Directory> =
        (if (target.isAndroid) jniLibsRoot else hostLibRoot).dir(target.libraryDirectoryName)

    // Only per-ABI libraries may live under jniLibs: AGP reads each subdirectory name as an ABI, and a
    // host library sitting in the root fails its merge with "… is not an ABI".
    internal val jniLibsRoot: DirectoryProperty = objects.directoryProperty()
    internal val hostLibRoot: DirectoryProperty = objects.directoryProperty()
}
