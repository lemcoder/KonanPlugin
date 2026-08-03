package io.github.lemcoder.jvm

import io.github.lemcoder.KonanTarget
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for generating JVM/Android **JNI** bindings from C headers, using the JVM flavor of
 * Kotlin/Native's `cinterop` stub generator. Configured via the nested `konanConfig { jvmInterop { } }`
 * block.
 *
 * The plugin produces two tiers of output for each library:
 *  - `generateJvmInterop` -> runtime-free Kotlin `external fun kniBridgeN(...)` declarations plus the
 *    JNI `.c` bridges. The Kotlin is stripped of `kotlinx.cinterop`, so it compiles and runs on plain
 *    JVM and Android with no extra runtime.
 *  - `linkJvmInterop` -> a self-contained shared library `lib<name>stubs.{so,dylib,dll}` per target,
 *    the JNI `.c` compiled and statically linked against the `.a` that `runKonanClang` produced.
 *
 * Every input here is derived from the enclosing `konanConfig` block — headers are scanned from its
 * `headerDir`, targets/static library/konan distribution come from its values — so in practice only
 * [packageName] needs setting, and even that defaults to the Android `namespace` when AGP is applied.
 * The properties remain settable to override a derived value.
 *
 * The user is expected to write the idiomatic API on top of the generated bridges.
 */
interface JvmInteropExtension {
    /**
     * Whether to generate JNI bindings. Defaults to `true` when a `jvmInterop { }` block is present
     * or when `konanConfig.targets` contains an Android target; set it explicitly to override.
     */
    val enabled: Property<Boolean>

    /** Kotlin package for the generated bindings. Defaults to the Android `namespace` if AGP is applied. */
    val packageName: Property<String>

    /** Header file names (resolved against [headerDir]) to bind. Defaults to every `.h` under [headerDir]. */
    val headers: ListProperty<String>

    /** `.def` `headerFilter` glob. Optional; defaults to the header names. */
    val headerFilter: Property<String>

    /** Include root holding the headers. Defaults to `konanConfig.headerDir`. */
    val headerDir: Property<String>

    /** Targets to build the JNI shared library for. Defaults to `konanConfig.targets`. */
    val targets: ListProperty<KonanTarget>

    /**
     * Root directory containing the per-target static libraries to link into the JNI stub, laid out as
     * `<staticLibraryDir>/<target>/lib<staticLibraryName>.a`. Defaults to `konanConfig.outputDir`.
     */
    val staticLibraryDir: Property<String>

    /** Base name of the static library to link. Defaults to `konanConfig.libName`. */
    val staticLibraryName: Property<String>

    /** Root of the Kotlin/Native distribution. Defaults to `konanConfig.konanPath`. */
    val konanPath: Property<String>

    /** A JDK home that ships `include/jni.h` (host targets only). Defaults to a scan of installed JDKs. */
    val jniHome: Property<String>

    /** Extra `-compiler-option` values forwarded to the binding generator's clang. */
    val additionalCompilerArgs: ListProperty<String>

    /** Extra arguments appended to the native link command of every target. */
    val additionalLinkerArgs: ListProperty<String>

    /**
     * Extra link arguments for a single target, appended after [additionalLinkerArgs]. Platform
     * specifics belong here — Apple frameworks, the C++ runtime — since one flat list reaches the
     * Android linker too, which rejects `-framework` outright. Prefer the [linkerArgsFor] helper.
     */
    val targetLinkerArgs: MapProperty<KonanTarget, List<String>>

    /** Appends [args] to the link command of [target] only. */
    fun linkerArgsFor(target: KonanTarget, vararg args: String) {
        // Read eagerly: a provider derived from the property it is then written back to is circular.
        val existing = targetLinkerArgs.getOrElse(emptyMap())[target].orEmpty()
        targetLinkerArgs.put(target, existing + args)
    }
}
