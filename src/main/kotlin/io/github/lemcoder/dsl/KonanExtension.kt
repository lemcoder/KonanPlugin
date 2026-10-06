package io.github.lemcoder.dsl

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
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
    /**
     * The Kotlin/Native distribution that compiles libraries and links JNI stubs. Defaults to
     * `$KONAN_HOME`, else the newest `~/.konan/kotlin-native-prebuilt-*`, found when a task runs.
     */
    abstract val konanHome: DirectoryProperty

    /** Native libraries this project builds or brings in, one archive per target. */
    val libraries: NamedDomainObjectContainer<NativeLibrarySpec> =
        objects.domainObjectContainer(NativeLibrarySpec::class.java)

    /** C APIs to reach from Kotlin, each described by a cinterop `.def`. */
    val bindings: NamedDomainObjectContainer<BindingSpec> =
        objects.domainObjectContainer(BindingSpec::class.java)

    fun libraries(action: Action<NamedDomainObjectContainer<NativeLibrarySpec>>) = action.execute(libraries)

    fun bindings(action: Action<NamedDomainObjectContainer<BindingSpec>>) = action.execute(bindings)
}
