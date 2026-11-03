package io.github.lemcoder.dsl.builds

import io.github.lemcoder.model.Executable
import io.github.lemcoder.model.NativeTarget
import org.gradle.api.Action
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import java.io.File
import javax.inject.Inject

/**
 * Any build system, as a list of commands run once per target — for a library (`libraries { external
 * { } }`), a JNI stub (`jni { external { } }`) or the library FFM loads (`ffm { external { } }`). Each run
 * gets the plugin's contract as environment variables, see [BuildSettings]. The build writes its
 * library where the contract says, or names where it leaves it with [producedLibrary], and the plugin
 * copies it into place.
 */
abstract class StepsSettings @Inject constructor(private val objects: ObjectFactory) : ExternalBuildSettings {
    init {
        toolchain.convention(ExternalToolchain.SYSTEM)
    }

    /**
     * Whose compilers the build gets. None by default: the steps are the build script's own. Set it to
     * [ExternalToolchain.KONAN] or [ExternalToolchain.AUTO] to have Kotlin/Native's in the environment
     * (`CC`, `CXX`, `AR`, `KONAN_CMAKE_TOOLCHAIN_FILE`).
     */
    abstract val toolchain: Property<ExternalToolchain>

    /**
     * What the build reads. With none, it runs every time and its own incrementality decides; with them,
     * it is up to date while they and its commands are unchanged.
     */
    abstract val sources: ConfigurableFileCollection

    /** Where the build leaves each target's library, when it does not write it to `KONAN_OUTPUT_DIR`. */
    abstract val producedLibraries: MapProperty<NativeTarget, File>

    /** The library the build leaves at [file] for [target]; the plugin copies and renames it into place. */
    fun producedLibrary(target: NativeTarget, file: File) = producedLibraries.put(target, file)

    /** Appends a command; commands run in declaration order, each only after the previous succeeded. */
    fun step(action: Action<in StepSpec>) {
        steps += objects.newInstance(StepSpec::class.java).also { action.execute(it) }
    }

    internal val steps = mutableListOf<StepSpec>()
}

/** One command of a [StepsSettings] build. */
abstract class StepSpec {
    /** The program to run. Required. */
    abstract val executable: Property<Executable>

    /** Arguments for every target. */
    abstract val arguments: ListProperty<String>

    /** Arguments for one target, after [arguments]. */
    abstract val targetArguments: MapProperty<NativeTarget, List<String>>

    /** Variables beyond the plugin's contract; these win over it. */
    abstract val environment: MapProperty<String, String>

    /** Where the command runs. Defaults to the Gradle daemon's own working directory. */
    abstract val workingDirectory: DirectoryProperty

    /** Appends [arguments] for [target] only. */
    fun argumentsFor(target: NativeTarget, vararg arguments: String) {
        val existing = targetArguments.getOrElse(emptyMap())[target].orEmpty()
        targetArguments.put(target, existing + arguments)
    }

    /** Sets [target]'s arguments from a provider, e.g. one built from `jni.libraryDirectoryFor(target)`. */
    fun argumentsFor(target: NativeTarget, arguments: Provider<List<String>>) = targetArguments.put(target, arguments)
}
