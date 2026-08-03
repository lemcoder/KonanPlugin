package io.github.lemcoder.interop

import org.jetbrains.kotlin.konan.target.KonanTarget
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import java.io.File
import javax.inject.Inject

/**
 * One JNI interop, declared the way a cinterop is:
 *
 * ```kotlin
 * kotlin {
 *     jvm {
 *         compilations["main"].jvmInterops {
 *             create("koinference") {
 *                 defFile(project.file("src/nativeInterop/koinference.def"))
 *                 packageName.set("com.example.native")
 *             }
 *         }
 *     }
 * }
 * ```
 *
 * The def decides how far the plugin goes. With `staticLibraries` (or [library]) it compiles the
 * generated JNI stub and links a shared library per target; without, it stops after generating the
 * Kotlin bindings and the `.c`, leaving the link to whoever owns the native build.
 */
abstract class JvmInteropSettings @Inject constructor(
    private val name: String,
    private val objects: ObjectFactory,
) : Named {

    override fun getName(): String = name

    /**
     * The cinterop `.def` describing the headers to bind, shared with the native targets. Defaults to
     * cinterop's own convention, `src/nativeInterop/cinterop/<name>.def`.
     */
    abstract val defFile: RegularFileProperty

    /** Kotlin package for the generated bindings. Overrides `package` in the def. */
    abstract val packageName: Property<String>

    /**
     * Targets to link the JNI library for. Defaults to the build host, which is the only one a JVM
     * running this build could load; add Android ABIs to produce `jniLibs`.
     */
    abstract val targets: ListProperty<KonanTarget>

    /**
     * Archive to link into the JNI library, overriding the def's `staticLibraries`. Prefer a library
     * this plugin built: linking one from another toolchain can fail on mismatched C++ runtimes.
     */
    abstract val library: RegularFileProperty

    /** Extra include roots, on top of the def's own directory. */
    abstract val includeDirs: ConfigurableFileCollection

    /** A JDK home that ships `include/jni.h`. Defaults to a scan of installed JDKs. */
    abstract val jniHome: Property<String>

    /** Extra `-compiler-option` values for the binding generator. */
    abstract val additionalCompilerArgs: ListProperty<String>

    /** Extra arguments for every target's link command. */
    abstract val additionalLinkerArgs: ListProperty<String>

    /** Extra link arguments for one target, appended after [additionalLinkerArgs]. */
    abstract val targetLinkerArgs: MapProperty<KonanTarget, List<String>>

    /** Sets [defFile]; mirrors cinterop's `defFile(project.file(...))`. */
    fun defFile(file: File) {
        defFile.set(file)
    }

    /**
     * Declares that another build system compiles and links the stub — see [ExternalNativeBuildSettings].
     * Mutually exclusive with the plugin linking it: no link task is registered once this is declared.
     */
    val externalNativeBuild: ExternalNativeBuildSettings =
        objects.newInstance(ExternalNativeBuildSettings::class.java)

    /** Configures the external build that compiles the generated stub. */
    fun externalNativeBuild(action: Action<in ExternalNativeBuildSettings>) {
        action.execute(externalNativeBuild)
    }

    /**
     * Where the linked JNI library ends up, whoever linked it. Use it to put the library on
     * `java.library.path` for tests, or to package it.
     */
    abstract val resolvedLibraryDirectory: DirectoryProperty

    /** Appends [args] to the link command of [target] only. */
    fun linkerArgsFor(target: KonanTarget, vararg args: String) {
        val existing = targetLinkerArgs.getOrElse(emptyMap())[target].orEmpty()
        targetLinkerArgs.put(target, existing + args)
    }
}
