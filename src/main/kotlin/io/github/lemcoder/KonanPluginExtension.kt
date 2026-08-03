package io.github.lemcoder

import io.github.lemcoder.jvm.JvmInteropExtension
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Configuration for the `konanConfig { }` block: cross-compiles `sourceDir`'s C/C++ to a static
 * `lib<libName>.a` per target using Kotlin/Native's `run_konan clang`.
 *
 * Everything except [libName] and [targets] has a convention, so a minimal build is:
 *
 * ```kotlin
 * konanConfig {
 *     targets(KonanTarget.ANDROID_ARM64, KonanTarget.ANDROID_X64)
 *     libName.set("mymath")
 *     jvmInterop { packageName.set("example") }
 * }
 * ```
 *
 * The nested [jvmInterop] block generates JNI bindings on top of the same sources; all of its
 * inputs default to the matching value here, so it usually needs nothing but a package name.
 */
abstract class KonanPluginExtension @Inject constructor(objects: ObjectFactory) {

    /** Targets to cross-compile for. Required. */
    abstract val targets: ListProperty<KonanTarget>

    /** Directory holding the C/C++ sources, relative to the project. Defaults to `native`. */
    abstract val sourceDir: Property<String>

    /** Include root passed as `-I`, relative to the project. Defaults to [sourceDir]. */
    abstract val headerDir: Property<String>

    /** Static library base name, without `lib` prefix or `.a` suffix. Defaults to the project name. */
    abstract val libName: Property<String>

    /** Root for the per-target `.a`s (`<outputDir>/<target>/lib<libName>.a`). Defaults to `build/native`. */
    abstract val outputDir: Property<String>

    /**
     * Root of the Kotlin/Native distribution. Defaults to `$KONAN_HOME`, else the newest
     * `~/.konan/kotlin-native-prebuilt-*`. Set this only to override the auto-detected one.
     */
    abstract val konanPath: Property<String>

    /**
     * Extra clang arguments, appended to the plugin's default (`-fno-sanitize=undefined`). A language
     * standard belongs here rather than in the defaults: all sources compile in one invocation, so
     * `-std=c99` would break a source dir that also holds C++.
     */
    abstract val additionalCompilerArgs: ListProperty<String>

    /** JNI binding generation on top of the same sources. See [JvmInteropExtension]. */
    val jvmInterop: JvmInteropExtension = objects.newInstance(JvmInteropExtension::class.java)

    /**
     * Set when the build declares a `jvmInterop { }` block, which opts the JNI leg in regardless of
     * the configured targets. Backs the convention of [JvmInteropExtension.enabled].
     */
    internal var jvmInteropDeclared: Boolean = false
        private set

    /** Configures the nested JNI interop block, and enables the JNI leg. */
    fun jvmInterop(action: Action<in JvmInteropExtension>) {
        jvmInteropDeclared = true
        action.execute(jvmInterop)
    }

    /** Convenience for `targets.set(listOf(...))`. */
    fun targets(vararg targets: KonanTarget) = this.targets.set(targets.toList())

    /** Convenience for `targets.set(...)` from Kotlin/Native target names, e.g. `"android_arm64"`. */
    fun targets(vararg konanNames: String) = targets.set(konanNames.map(KonanTarget::fromKonanName))

    /** All four Android ABIs. */
    fun androidTargets() = targets.set(KonanTarget.ANDROID)
}
