package io.github.lemcoder.jvm

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * Configuration for generating JVM/Android **JNI** bindings from C headers, using the JVM flavor of
 * Kotlin/Native's `cinterop` stub generator. Configured via the `jvmInterop { }` block.
 *
 * The plugin produces two tiers of output for each library:
 *  - `generateJvmInterop` -> runtime-free Kotlin `external fun kniBridgeN(...)` declarations plus the
 *    JNI `.c` bridges. The Kotlin is stripped of `kotlinx.cinterop`, so it compiles and runs on plain
 *    JVM and Android with no extra runtime.
 *  - `linkJvmInterop` -> a self-contained shared library `lib<name>stubs.{so,dylib,dll}` per target,
 *    the JNI `.c` compiled and statically linked against [staticLibraryName].
 *
 * The user is expected to write the idiomatic `expect`/`actual` API on top of the generated bridges.
 */
interface JvmInteropExtension {
    /** Header file names (resolved against [headerDir]) to bind, e.g. `["mymath.h"]`. */
    val headers: ListProperty<String>

    /** Kotlin package for the generated bindings, e.g. `"io.example.mymath"`. */
    val packageName: Property<String>

    /** `.def` `headerFilter` glob. Optional; defaults to the header names. */
    val headerFilter: Property<String>

    /** Directory (relative to the project) holding the headers and used as the `-I` include root. */
    val headerDir: Property<String>

    /** Targets to build the JNI shared library for, e.g. `["macos_arm64", "android_arm64"]`. */
    val targets: ListProperty<String>

    /**
     * Root directory containing the per-target static libraries to link into the JNI stub, laid out as
     * `<staticLibraryDir>/<target>/lib<staticLibraryName>.a` (the layout `runKonanClang` produces).
     */
    val staticLibraryDir: Property<String>

    /** Base name of the static library to link (without `lib` prefix / `.a` suffix). */
    val staticLibraryName: Property<String>

    /** Root of the Kotlin/Native distribution. Defaults to the newest `~/.konan/kotlin-native-prebuilt-*` or `$KONAN_HOME`. */
    val konanPath: Property<String>

    /** A JDK home that ships `include/jni.h` (host targets only). Defaults to a scan of installed JDKs. */
    val jniHome: Property<String>

    /** Extra `-compiler-option` values forwarded to the binding generator's clang. */
    val additionalCompilerArgs: ListProperty<String>

    /** Extra arguments appended to the native link command (per target). */
    val additionalLinkerArgs: ListProperty<String>
}