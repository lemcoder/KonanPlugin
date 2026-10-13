package io.github.lemcoder.dsl

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * The `konan { }` block: the native libraries a project builds, and the bindings that reach them from
 * Kotlin.
 *
 * ```kotlin
 * konan {
 *     val mymath = libraries.create("mymath") {
 *         targets(NativeTarget.MACOS_ARM64, NativeTarget.ANDROID_ARM64)
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
    /** The paths the plugin needs from the machine: the Kotlin/Native distribution and the programs it runs. */
    val config: KonanConfig = objects.newInstance(KonanConfig::class.java)

    fun config(action: Action<in KonanConfig>) = action.execute(config)

    /**
     * The Android API level whose platform libraries the plugin's Android links see: `libaaudio`,
     * `libcamera2ndk` and the other later ones are missing from the API 21 Kotlin/Native links against.
     * Defaults to the module's lowest `minSdk`, else Kotlin/Native's; Kotlin/Native's NDK stops at 29, so
     * a higher level links against the highest it has.
     */
    abstract val androidApiLevel: Property<Int>

    /** Native libraries this project builds or brings in, one archive per target. */
    val libraries: NamedDomainObjectContainer<NativeLibrarySpec> =
        objects.domainObjectContainer(NativeLibrarySpec::class.java)

    /** C APIs to reach from Kotlin, each described by a cinterop `.def`. */
    val bindings: NamedDomainObjectContainer<BindingSpec> =
        objects.domainObjectContainer(BindingSpec::class.java)

    fun libraries(action: Action<NamedDomainObjectContainer<NativeLibrarySpec>>) = action.execute(libraries)

    fun bindings(action: Action<NamedDomainObjectContainer<BindingSpec>>) = action.execute(bindings)
}
