package io.github.lemcoder.dsl

import io.github.lemcoder.model.Executable
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** How the FFM bindings are generated. The generated Java needs a JDK 22+ toolchain. */
abstract class FfmSpec {
    init {
        jextract.convention(Executable.OnPath("jextract"))
    }

    /** The jextract program. Defaults to `jextract` on the PATH or in the usual install locations. */
    abstract val jextract: Property<Executable>

    /** Extra options for linking the library the bindings load. */
    abstract val linkerOptions: ListProperty<String>

    /**
     * Where the shared library the bindings load ends up, for the build host: put it on
     * `java.library.path`, together with `--enable-native-access`.
     */
    abstract val libraryDirectory: DirectoryProperty

    internal var isEnabled: Boolean = false
}
