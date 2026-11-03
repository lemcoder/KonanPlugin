package io.github.lemcoder.dsl.bindings.jni

import io.github.lemcoder.dsl.bindings.BindingLibrarySpec
import io.github.lemcoder.model.BindingVisibility
import javax.inject.Inject
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider

/**
 * How the JNI bindings are generated, and the JNI library they load: the generated `.c` stub linked with
 * the binding's library — by the plugin, or by the project's own build, which gets `KONAN_JNI_STUB_DIR`
 * and `KONAN_JNI_INCLUDE_DIR` besides the common contract. [targets] default to the library's targets a
 * JVM can load — the build host and Android — or the host when there is no library.
 */
abstract class JniSpec @Inject constructor(objects: ObjectFactory) : BindingLibrarySpec(objects) {
    init {
        visibility.convention(BindingVisibility.INTERNAL)
    }

    /**
     * Visibility of the generated bindings. They are an implementation detail — the idiomatic API is
     * written on top of them — so they are `internal` by default.
     */
    abstract val visibility: Property<BindingVisibility>

    /** The directory holding the generated `.c` stub — what an external build compiles. */
    val stubDirectory: Provider<Directory> get() = stubDirectoryProperty

    /** The library name the generated bindings load: an external build must produce exactly that. */
    val stubLibraryName: Provider<String> get() = stubLibraryNameProperty

    internal val stubDirectoryProperty: DirectoryProperty = objects.directoryProperty()
    internal val stubLibraryNameProperty: Property<String> = objects.property(String::class.java)
}
