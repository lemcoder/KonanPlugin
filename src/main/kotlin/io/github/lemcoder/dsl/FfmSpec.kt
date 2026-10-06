package io.github.lemcoder.dsl

import io.github.lemcoder.model.Executable
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** How the FFM bindings are generated. The generated Java needs a JDK 22+ toolchain. */
abstract class FfmSpec {
    init {
        jextract.convention(Executable.OnPath("jextract"))
        panamaPortVersion.convention("v0.1.5")
    }

    /** The jextract program. Defaults to `jextract` on the PATH or in the usual install locations. */
    abstract val jextract: Property<Executable>

    /**
     * The version of PanamaPort (`io.github.vova7878.panama:Core`), the FFM backport an Android module's
     * bindings use; the plugin adds it as an `implementation` dependency. It needs `minSdk` 26.
     */
    abstract val panamaPortVersion: Property<String>

    /** Extra options for linking the library the bindings load. */
    abstract val linkerOptions: ListProperty<String>

    /**
     * Where the shared library the bindings load ends up: for the build host, to put on
     * `java.library.path` together with `--enable-native-access`; in an Android module, the `jniLibs`
     * root the plugin packages.
     */
    abstract val libraryDirectory: DirectoryProperty

    internal var isEnabled: Boolean = false
}
