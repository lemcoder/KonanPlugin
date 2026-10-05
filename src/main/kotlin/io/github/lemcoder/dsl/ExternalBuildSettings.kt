package io.github.lemcoder.dsl

import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.io.File
import javax.inject.Inject

/**
 * Any build system, as a list of commands run once per JNI target. Each run gets the plugin's contract
 * as environment variables — `KONAN_JNI_STUB_DIR`, `KONAN_JNI_LIB_NAME`, `KONAN_JNI_OUTPUT_DIR`,
 * `KONAN_TARGET`, `KONAN_JNI_ABI`, `JAVA_HOME` — and the same values are on `jni` as providers, for
 * arguments. The build writes the library into `KONAN_JNI_OUTPUT_DIR`, or names where it leaves it with
 * [producedLibrary].
 */
abstract class ExternalBuildSettings @Inject constructor(private val objects: ObjectFactory) {
    /**
     * What the build reads. With none, it runs every time and its own incrementality decides; with them,
     * it is up to date while they and its commands are unchanged.
     */
    abstract val sources: ConfigurableFileCollection

    /** Where the build leaves each target's library, when it does not write it to `KONAN_JNI_OUTPUT_DIR`. */
    abstract val producedLibraries: MapProperty<KonanTarget, File>

    /** The library the build leaves at [file] for [target]; the plugin copies and renames it into place. */
    fun producedLibrary(target: KonanTarget, file: File) = producedLibraries.put(target, file)

    /** Appends a command; commands run in declaration order, each only after the previous succeeded. */
    fun step(action: Action<in ExternalStepSpec>) {
        steps += objects.newInstance(ExternalStepSpec::class.java).also { action.execute(it) }
    }

    internal val steps = mutableListOf<ExternalStepSpec>()
}

/** One command of an [ExternalBuildSettings] build. */
abstract class ExternalStepSpec {
    /** The program to run. Required. */
    abstract val executable: Property<Executable>

    /** Arguments for every target. */
    abstract val arguments: ListProperty<String>

    /** Arguments for one target, after [arguments]. */
    abstract val targetArguments: MapProperty<KonanTarget, List<String>>

    /** Variables beyond the plugin's contract; these win over it. */
    abstract val environment: MapProperty<String, String>

    /** Where the command runs. Defaults to the Gradle daemon's own working directory. */
    abstract val workingDirectory: DirectoryProperty

    /** Appends [arguments] for [target] only. */
    fun argumentsFor(target: KonanTarget, vararg arguments: String) {
        val existing = targetArguments.getOrElse(emptyMap())[target].orEmpty()
        targetArguments.put(target, existing + arguments)
    }

    /** Sets [target]'s arguments from a provider, e.g. one built from `jni.libraryDirectoryFor(target)`. */
    fun argumentsFor(target: KonanTarget, arguments: Provider<List<String>>) = targetArguments.put(target, arguments)
}
