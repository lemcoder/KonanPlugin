package io.github.lemcoder.dsl.bindings.ffm

import io.github.lemcoder.dsl.bindings.BindingLibrarySpec
import io.github.lemcoder.dsl.builds.PrebuiltSettings
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/**
 * How the FFM bindings are generated, and the shared library they load: linked by the plugin from the
 * binding's library (the default), built by the project's own build, or built elsewhere ([prebuilt]). FFM
 * needs no stub — this is the C library itself. On a JVM the generated Java needs a JDK 22+ toolchain.
 * [targets] are the build host on a JVM and 64-bit ABIs in an Android module — jextract writes LP64
 * layouts — defaulting to the binding's library's targets among those.
 */
abstract class FfmSpec @Inject constructor(objects: ObjectFactory) : BindingLibrarySpec(objects) {
    init {
        panamaPortVersion.convention("v0.1.5")
    }

    /**
     * The version of PanamaPort (`io.github.vova7878.panama:Core`), the FFM backport an Android module's
     * bindings use; the plugin adds it as an `implementation` dependency. It needs `minSdk` 26.
     */
    abstract val panamaPortVersion: Property<String>

    /**
     * The library the bindings load: `System.loadLibrary(libraryName)`, so `lib<libraryName>.so`. Defaults
     * to the binding's library's name, else the binding's. Set it to a prebuilt library's own name so that
     * libraries depending on it by that name still find it.
     */
    abstract val libraryName: Property<String>

    /** Shared libraries built elsewhere, one per target. */
    val prebuilt: PrebuiltSettings = objects.newInstance(PrebuiltSettings::class.java)

    fun prebuilt(action: Action<in PrebuiltSettings>) = select(prebuilt, action)

    internal var isEnabled: Boolean = false
}
